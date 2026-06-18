"""
================================================================================
  RAG Pipeline: FAISS (ANN) + BM25 混合检索 + 自动评估
================================================================================
  数据集:
    - 知识库: eval/dinosaur_baike.txt  (恐龙百科中文全文)
    - QA 测试集: eval/dataset.json      (60 个中文问答对)

  流程:
    1. 知识库分块(chunk)
    2. 构建双路索引: FAISS (稠密向量) + BM25 (稀疏词频)
    3. 混合检索: 加权融合两路分数
    4. 答案生成: 基于检索结果的 extractive QA (后续可替换为 LLM)
    5. 自动评估: 检索召回率/准确率 + 答案 EM/F1

================================================================================
  📦 安装依赖 (请先运行):
  pip install faiss-cpu rank-bm25 sentence-transformers scikit-learn numpy jieba tqdm
================================================================================
"""

import json
import re
import math
import time
import numpy as np
from typing import List, Dict, Tuple, Optional, Callable
from dataclasses import dataclass, field
from collections import Counter
import warnings
warnings.filterwarnings('ignore')

# ──────────────────────────────────────────────────────────────────
#  0. 依赖检查
# ──────────────────────────────────────────────────────────────────

def check_dependencies():
    """检查关键依赖是否存在，缺失时给出安装提示"""
    deps = [
        ('numpy',         'numpy'),
        ('faiss',         'faiss-cpu'),
        ('rank_bm25',     'rank-bm25'),
        ('sentence_transformers', 'sentence-transformers'),
        ('jieba',         'jieba'),
        ('sklearn',       'scikit-learn'),
        ('tqdm',          'tqdm'),
    ]
    missing = []
    for mod_name, pip_name in deps:
        try:
            __import__(mod_name)
        except ImportError:
            missing.append(pip_name)
    if missing:
        print('❌ 缺少依赖，请先安装:')
        print(f'   pip install {" ".join(missing)}')
        print()
        return False
    print('✅ 所有依赖已就绪')
    return True


# ──────────────────────────────────────────────────────────────────
#  1. 数据加载
# ──────────────────────────────────────────────────────────────────

def load_corpus(path: str) -> str:
    """加载恐龙百科全文"""
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    print(f'📖 知识库加载完成: {len(text)} 字符')
    return text


def load_qa(path: str) -> List[Dict]:
    """加载 Q&A 测试集"""
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    print(f'📋 QA 测试集加载完成: {len(data)} 条')
    return data


# ──────────────────────────────────────────────────────────────────
#  2. 文本分块 (Chunking)
# ──────────────────────────────────────────────────────────────────

def chunk_text_by_sections(
    text: str,
    section_pattern: str = r'\n{2,}(?=[一-鿿]{2,8}\n)',
    max_chunk_size: int = 300,
    overlap: int = 30,
) -> List[Dict]:
    """
    按章节 + 段落混合分块，适合中文百科类文档。

    参数:
      section_pattern: 章节标题的正则（默认匹配中文标题行）
      max_chunk_size:  最大块字符数（按字符切）
      overlap:         相邻块重叠字符数

    返回:
      [{'id': 'chunk_000', 'text': '...', 'section': '...'}, ...]
    """
    # 1) 按章节标题切分
    sections = re.split(section_pattern, text)
    sections = [s.strip() for s in sections if len(s.strip()) > 20]

    chunks = []
    for sec_idx, sec_text in enumerate(sections):
        # 取该段的第一行作为 section 名
        first_line = sec_text.split('\n')[0][:30]

        # 2) 如果段落较短，直接作为一个 chunk
        if len(sec_text) <= max_chunk_size:
            chunks.append({
                'id': f'chunk_{len(chunks):04d}',
                'text': sec_text,
                'section': first_line,
            })
            continue

        # 3) 长段落按句号/换行切分后合并为固定大小块
        sentences = re.split(r'(?<=[。！？\n])', sec_text)
        sentences = [s.strip() for s in sentences if len(s.strip()) > 5]

        buffer = ''
        for sent in sentences:
            if len(buffer) + len(sent) <= max_chunk_size:
                buffer += sent
            else:
                if buffer:
                    chunks.append({
                        'id': f'chunk_{len(chunks):04d}',
                        'text': buffer,
                        'section': first_line,
                    })
                # 带 overlap: 保留 buffer 末尾 overlap 字符
                if overlap > 0 and len(buffer) > overlap:
                    buffer = buffer[-overlap:] + sent
                else:
                    buffer = sent

        if buffer:
            chunks.append({
                'id': f'chunk_{len(chunks):04d}',
                'text': buffer,
                'section': first_line,
            })

    print(f'✂️  知识库分块完成: {len(chunks)} 个 chunks')
    return chunks


# ──────────────────────────────────────────────────────────────────
#  3. 混合检索器 (FAISS + BM25)
# ──────────────────────────────────────────────────────────────────

class HybridRetriever:
    """
    FAISS (ANN 稠密向量) + BM25 (稀疏词频) 混合检索器。

    融合策略: score = α * dense_norm + (1-α) * sparse_norm
    其中 α 为 dense 权重，默认为 0.5。
    """

    def __init__(
        self,
        embedding_model_name: str = 'distiluse-base-multilingual-cased-v2',
        alpha: float = 0.5,
        use_jieba: bool = True,
    ):
        """
        参数:
          embedding_model_name: sentence-transformers 模型名（中文推荐多语言模型）
          alpha:               稠密检索权重 [0,1]，1=纯 FAISS，0=纯 BM25
          use_jieba:           是否使用 jieba 分词（中文 BM25 需要）
        """
        self.alpha = alpha
        self.use_jieba = use_jieba
        self.encoder = None
        self.faiss_index = None
        self.bm25 = None
        self.chunks: List[Dict] = []
        self.doc_texts: List[str] = []
        self.doc_ids: List[str] = []

    def build_index(self, chunks: List[Dict]):
        """
        构建 FAISS + BM25 双路索引。

        参数:
          chunks: [{'id': ..., 'text': ..., 'section': ...}, ...]
        """
        from sentence_transformers import SentenceTransformer
        import faiss
        from rank_bm25 import BM25Okapi

        self.chunks = chunks
        self.doc_texts = [c['text'] for c in chunks]
        self.doc_ids = [c['id'] for c in chunks]

        print(f'🔨 加载嵌入模型: {self.encoder or "(not set)"} ...')
        model_name = self.encoder if self.encoder else 'distiluse-base-multilingual-cased-v2'
        self.encoder = SentenceTransformer(model_name)
        print(f'   模型加载完成, dim={self.encoder.get_sentence_embedding_dimension()}')

        # ── 稠密索引 (FAISS) ──
        print('🔨 编码文档并构建 FAISS 索引 ...')
        embeddings = self.encoder.encode(
            self.doc_texts, show_progress_bar=True, batch_size=64, normalize_embeddings=True
        )
        dim = embeddings.shape[1]
        # IP 索引 + 归一化 = 余弦相似度
        self.faiss_index = faiss.IndexFlatIP(dim)
        self.faiss_index.add(embeddings.astype(np.float32))
        print(f'   FAISS 索引完成: {self.faiss_index.ntotal} 条向量')

        # ── 稀疏索引 (BM25) ──
        print('🔨 构建 BM25 索引 ...')
        if self.use_jieba:
            import jieba
            tokenized = [list(jieba.cut(t)) for t in self.doc_texts]
        else:
            tokenized = [list(t) for t in self.doc_texts]
        self.bm25 = BM25Okapi(tokenized)
        print(f'   BM25 索引完成: {len(self.doc_texts)} 篇文档')

        print('✅ 双路索引构建完成')

    def retrieve(
        self,
        query: str,
        k: int = 5,
        alpha: Optional[float] = None,
    ) -> List[Dict]:
        """
        混合检索: 融合 FAISS + BM25 得分。

        参数:
          query: 查询文本
          k:     返回 top-k 结果
          alpha: 临时覆盖 self.alpha（稠密权重）

        返回:
          [{'id': ..., 'text': ..., 'section': ...,
            'score': ..., 'dense_score': ..., 'sparse_score': ...}, ...]
        """
        import faiss

        a = alpha if alpha is not None else self.alpha
        n = len(self.doc_texts)
        if n == 0:
            return []

        # ── 稠密检索 ──
        query_emb = self.encoder.encode([query], normalize_embeddings=True).astype(np.float32)
        dense_scores_all = np.zeros(n, dtype=np.float32)
        # 全量打分
        dense_scores_all = self.faiss_index.search(query_emb, n)[1][0]  # 其实是索引+分数...修复

        # 正确方式: search 返回 (distances, indices)
        dense_dist, dense_idx = self.faiss_index.search(query_emb, n)
        dense_dist = dense_dist[0]  # (n,)
        dense_idx = dense_idx[0]    # (n,)
        dense_scores = np.zeros(n, dtype=np.float32)
        dense_scores[dense_idx] = dense_dist

        # ── 稀疏检索 ──
        if self.use_jieba:
            import jieba
            query_tokens = list(jieba.cut(query))
        else:
            query_tokens = list(query)
        sparse_scores = np.array(self.bm25.get_scores(query_tokens), dtype=np.float32)

        # ── 归一化 ──
        def _norm(scores: np.ndarray) -> np.ndarray:
            mn, mx = scores.min(), scores.max()
            if mx - mn < 1e-12:
                return np.zeros_like(scores)
            return (scores - mn) / (mx - mn)

        dense_norm = _norm(dense_scores)
        sparse_norm = _norm(sparse_scores)

        # ── 融合 ──
        combined = a * dense_norm + (1 - a) * sparse_norm

        # ── Top-K ──
        topk_idx = np.argsort(combined)[::-1][:k]

        results = []
        for idx in topk_idx:
            results.append({
                'id': self.doc_ids[idx],
                'text': self.doc_texts[idx],
                'section': self.chunks[idx].get('section', ''),
                'score': float(combined[idx]),
                'dense_score': float(dense_norm[idx]),
                'sparse_score': float(sparse_norm[idx]),
            })
        return results

    def retrieve_dense(self, query: str, k: int = 5) -> List[Dict]:
        """纯稠密检索 (FAISS)"""
        return self.retrieve(query, k, alpha=1.0)

    def retrieve_sparse(self, query: str, k: int = 5) -> List[Dict]:
        """纯稀疏检索 (BM25)"""
        return self.retrieve(query, k, alpha=0.0)

    def retrieve_ablation(
        self, query: str, k: int = 5
    ) -> Dict:
        """消融实验: 返回稠密/稀疏/混合三种结果"""
        return {
            'dense':  self.retrieve_dense(query, k),
            'sparse': self.retrieve_sparse(query, k),
            'hybrid': self.retrieve(query, k),
        }


# ──────────────────────────────────────────────────────────────────
#  4. 答案生成 (Extractive Baseline)
# ──────────────────────────────────────────────────────────────────

def extractive_answer(
    question: str,
    retrieved_chunks: List[Dict],
    strategy: str = 'first_match',
) -> str:
    """
    从检索到的 chunks 中提取答案（无 LLM 时的 baseline）。

    参数:
      question:        问题
      retrieved_chunks: 检索结果 (由 HybridRetriever.retrieve 返回)
      strategy:        提取策略:
        - 'first_match': 返回第一个与问题相关的完整句子
        - 'concat_top3': 拼接 top-3 chunk 的第一句
        - 'best_chunk':  返回 top-1 chunk

    返回:
      答案字符串
    """
    if not retrieved_chunks:
        return ''

    if strategy == 'best_chunk':
        return retrieved_chunks[0]['text'][:200]

    elif strategy == 'concat_top3':
        parts = []
        for c in retrieved_chunks[:3]:
            # 取第一句
            first_sent = c['text'].split('。')[0]
            if first_sent:
                parts.append(first_sent + '。')
        return ''.join(parts)

    elif strategy == 'first_match':
        # 提取与问题关键词重叠最多的句子
        q_words = set(question)
        best_sent, best_score = '', 0
        for c in retrieved_chunks:
            for sent in re.split(r'[。！？]', c['text']):
                sent = sent.strip()
                if len(sent) < 4:
                    continue
                overlap = sum(1 for w in sent if w in q_words)
                if overlap > best_score:
                    best_score = overlap
                    best_sent = sent
        return best_sent + ('。' if best_sent else '')

    return retrieved_chunks[0]['text'][:200] if retrieved_chunks else ''


# ──────────────────────────────────────────────────────────────────
#  5. 评估指标
# ──────────────────────────────────────────────────────────────────

def normalize_text(s: str) -> str:
    """标准化文本：去空格、标点、换行"""
    s = s.strip()
    s = re.sub(r'[\s,，。！？、；：""''（）\(\)\[\]【】《》　]+', '', s)
    return s.lower()


def char_f1_score(prediction: str, reference: str) -> float:
    """字符级 F1（适合中文评估）"""
    pred_chars = list(normalize_text(prediction))
    ref_chars = list(normalize_text(reference))
    if not ref_chars:
        return 1.0 if not pred_chars else 0.0
    common = Counter(pred_chars) & Counter(ref_chars)
    tp = sum(common.values())
    if tp == 0:
        return 0.0
    precision = tp / len(pred_chars)
    recall = tp / len(ref_chars)
    return 2 * precision * recall / (precision + recall)


def exact_match(prediction: str, reference: str) -> bool:
    """精确匹配（标准化后）"""
    return normalize_text(prediction) == normalize_text(reference)


def contains_answer(prediction: str, reference: str) -> bool:
    """参考答案是否包含在预测中（宽松匹配）"""
    ref_norm = normalize_text(reference)
    pred_norm = normalize_text(prediction)
    return ref_norm in pred_norm or pred_norm in ref_norm


# ──────────────────────────────────────────────────────────────────
#  6. 自动评估器
# ──────────────────────────────────────────────────────────────────

@dataclass
class RetrievalEvalResult:
    """检索评估结果"""
    recall_at_1: float = 0.0
    recall_at_3: float = 0.0
    recall_at_5: float = 0.0
    precision_at_1: float = 0.0
    precision_at_3: float = 0.0
    precision_at_5: float = 0.0
    mrr: float = 0.0
    map_score: float = 0.0


@dataclass
class AnswerEvalResult:
    """答案评估结果"""
    exact_match: float = 0.0
    char_f1: float = 0.0
    contains_rate: float = 0.0
    avg_f1_by_k: Dict[str, float] = field(default_factory=dict)


def evaluate_retrieval(
    retriever: HybridRetriever,
    qa_data: List[Dict],
    k_list: List[int] = [1, 3, 5],
) -> RetrievalEvalResult:
    """
    评估检索召回率与准确率。

    采用 "答案包含" 作为相关性判定：如果 chunk 文本中包含标准答案文字，
    则认为该 chunk 是相关的。这是无人工标注时的 proxy 方法。

    返回:
      RetrievalEvalResult
    """
    from tqdm import tqdm

    total = len(qa_data)
    recalls = {k: 0 for k in k_list}
    precisions = {k: 0 for k in k_list}
    reciprocal_ranks = []
    avg_precisions = []

    for item in tqdm(qa_data, desc='评估检索'):
        question = item['question']
        answer = item['answer']

        # 找出所有包含答案的 chunk 索引
        relevant_indices = set()
        for idx, text in enumerate(retriever.doc_texts):
            if normalize_text(answer) in normalize_text(text):
                relevant_indices.add(idx)

        if not relevant_indices:
            continue

        # 检索
        results = retriever.retrieve(question, k=max(k_list))
        retrieved_indices = []
        for r in results:
            for idx, c in enumerate(retriever.chunks):
                if c['id'] == r['id']:
                    retrieved_indices.append(idx)
                    break

        # ── Recall@k ──
        for k in k_list:
            retrieved_k = set(retrieved_indices[:k])
            hits = len(relevant_indices & retrieved_k)
            recalls[k] += hits / len(relevant_indices)

        # ── Precision@k ──
        for k in k_list:
            retrieved_k = retrieved_indices[:k]
            if retrieved_k:
                hits = len(relevant_indices & set(retrieved_k))
                precisions[k] += hits / k
            else:
                precisions[k] += 0.0

        # ── MRR ──
        for rank, idx in enumerate(retrieved_indices, 1):
            if idx in relevant_indices:
                reciprocal_ranks.append(1.0 / rank)
                break
        else:
            reciprocal_ranks.append(0.0)

        # ── MAP (Mean Average Precision) ──
        ap = 0.0
        hit_count = 0
        for rank, idx in enumerate(retrieved_indices, 1):
            if idx in relevant_indices:
                hit_count += 1
                ap += hit_count / rank
        if len(relevant_indices) > 0:
            ap /= len(relevant_indices)
        avg_precisions.append(ap)

    result = RetrievalEvalResult()
    for k in k_list:
        setattr(result, f'recall_at_{k}', recalls[k] / total)
        setattr(result, f'precision_at_{k}', precisions[k] / total)
    result.mrr = np.mean(reciprocal_ranks) if reciprocal_ranks else 0.0
    result.map_score = np.mean(avg_precisions) if avg_precisions else 0.0
    return result


def evaluate_answer(
    retriever: HybridRetriever,
    qa_data: List[Dict],
    retrieval_k: int = 5,
) -> AnswerEvalResult:
    """
    评估 RAG 答案质量。

    流程: 检索 → 答案提取 → 与标准答案对比
    使用 extractive_answer 作为生成器（后续可替换为 LLM）。

    返回:
      AnswerEvalResult
    """
    from tqdm import tqdm

    total = len(qa_data)
    em_count = 0
    f1_scores = []
    contains_count = 0
    f1_by_k = {}

    for k_test in [1, 3, 5]:
        f1_by_k[f'f1@{k_test}'] = []

    for item in tqdm(qa_data, desc='评估答案'):
        question = item['question']
        reference = item['answer']

        for k_test in [1, 3, 5]:
            results = retriever.retrieve(question, k=k_test)
            pred = extractive_answer(question, results, strategy='first_match')
            f1_by_k[k_test].append(char_f1_score(pred, reference))

        # 默认用 k=retrieval_k 做主要评估
        results = retriever.retrieve(question, k=retrieval_k)
        pred = extractive_answer(question, results, strategy='first_match')

        if exact_match(pred, reference):
            em_count += 1
        if contains_answer(pred, reference):
            contains_count += 1
        f1_scores.append(char_f1_score(pred, reference))

    result = AnswerEvalResult()
    result.exact_match = em_count / total
    result.char_f1 = np.mean(f1_scores)
    result.contains_rate = contains_count / total
    result.avg_f1_by_k = {f'f1@{k}': np.mean(v) for k, v in f1_by_k.items()}
    return result


# ──────────────────────────────────────────────────────────────────
#  7. 报告输出
# ──────────────────────────────────────────────────────────────────

def print_retrieval_report(result: RetrievalEvalResult, title: str = '检索评估报告'):
    """格式化输出检索评估结果"""
    print(f'\n{"="*60}')
    print(f'  📊 {title}')
    print(f'{"="*60}')
    print(f'  Recall@1 : {result.recall_at_1:.4f}')
    print(f'  Recall@3 : {result.recall_at_3:.4f}')
    print(f'  Recall@5 : {result.recall_at_5:.4f}')
    print(f'  Precision@1: {result.precision_at_1:.4f}')
    print(f'  Precision@3: {result.precision_at_3:.4f}')
    print(f'  Precision@5: {result.precision_at_5:.4f}')
    print(f'  MRR      : {result.mrr:.4f}')
    print(f'  MAP      : {result.map_score:.4f}')
    print(f'{"="*60}')


def print_answer_report(result: AnswerEvalResult, title: str = '答案评估报告'):
    """格式化输出答案评估结果"""
    print(f'\n{"="*60}')
    print(f'  📝 {title}')
    print(f'{"="*60}')
    print(f'  Exact Match : {result.exact_match:.4f}')
    print(f'  Char F1     : {result.char_f1:.4f}')
    print(f'  Contains    : {result.contains_rate:.4f}')
    for k, v in result.avg_f1_by_k.items():
        print(f'  {k}        : {v:.4f}')
    print(f'{"="*60}')


# ──────────────────────────────────────────────────────────────────
#  8. 消融对比实验
# ──────────────────────────────────────────────────────────────────

def run_ablation(
    retriever: HybridRetriever,
    qa_data: List[Dict],
):
    """
    对比纯稠密 / 纯稀疏 / 混合检索的效果。
    仅评估检索召回率，展示各检索方式的差异。
    """
    from copy import deepcopy

    results = {}
    for method, alpha_val in [('纯稠密 (FAISS)', 1.0), ('纯稀疏 (BM25)', 0.0), ('混合 (α=0.5)', 0.5)]:
        # 临时覆盖 alpha
        orig_alpha = retriever.alpha
        retriever.alpha = alpha_val
        eval_ret = evaluate_retrieval(retriever, qa_data)
        retriever.alpha = orig_alpha
        results[method] = eval_ret

    print(f'\n{"="*60}')
    print(f'  🔬 消融实验: 不同检索方式对比')
    print(f'{"="*60}')
    print(f'  {"方式":<20} {"R@1":<8} {"R@3":<8} {"R@5":<8} {"MRR":<8}')
    print(f'  {"-"*52}')
    for method, r in results.items():
        print(f'  {method:<20} {r.recall_at_1:<8.4f} {r.recall_at_3:<8.4f} '
              f'{r.recall_at_5:<8.4f} {r.mrr:<8.4f}')
    print(f'{"="*60}\n')

    return results


# ──────────────────────────────────────────────────────────────────
#  9. α 参数调优
# ──────────────────────────────────────────────────────────────────

def tune_alpha(
    retriever: HybridRetriever,
    qa_data: List[Dict],
    alphas: List[float] = [0.0, 0.2, 0.4, 0.5, 0.6, 0.8, 1.0],
) -> Tuple[float, Dict]:
    """
    网格搜索最佳稠密权重 α。
    以 Recall@3 为优化目标。

    返回:
      (best_alpha, {alpha: eval_result})
    """
    from copy import deepcopy

    print(f'\n{"="*60}')
    print(f'  🎯 α 参数调优 (目标: 最大化 Recall@3)')
    print(f'{"="*60}')
    print(f'  {"α":<8} {"R@1":<8} {"R@3":<8} {"R@5":<8} {"MRR":<8}')
    print(f'  {"-"*50}')

    orig_alpha = retriever.alpha
    results = {}
    best_alpha, best_r3 = 0.0, 0.0

    for a in alphas:
        retriever.alpha = a
        r = evaluate_retrieval(retriever, qa_data)
        results[a] = r
        marker = ' ← best' if r.recall_at_3 > best_r3 else ''
        if r.recall_at_3 > best_r3:
            best_alpha, best_r3 = a, r.recall_at_3
        print(f'  {a:<8.1f} {r.recall_at_1:<8.4f} {r.recall_at_3:<8.4f} '
              f'{r.recall_at_5:<8.4f} {r.mrr:<8.4f}{marker}')

    retriever.alpha = orig_alpha
    print(f'{"="*60}')
    print(f'  ✅ 最佳 α = {best_alpha:.1f} (Recall@3 = {best_r3:.4f})')
    print(f'{"="*60}\n')

    return best_alpha, results


# ──────────────────────────────────────────────────────────────────
# 10. 交互式 Demo
# ──────────────────────────────────────────────────────────────────

def interactive_demo(retriever: HybridRetriever, qa_data: List[Dict]):
    """交互式问答 Demo"""
    print(f'\n{"="*60}')
    print(f'  🎮 交互式 RAG Demo (输入 q 退出)')
    print(f'{"="*60}')

    while True:
        print()
        query = input('🔍 输入问题: ').strip()
        if query.lower() in ('q', 'quit', 'exit'):
            break
        if not query:
            continue

        results = retriever.retrieve(query, k=3)

        print(f'\n  ── Top-3 检索结果 ──')
        for i, r in enumerate(results, 1):
            print(f'  [{i}] ({r["section"]}) [score={r["score"]:.4f}]')
            print(f'      {r["text"][:120]}...')

        pred = extractive_answer(query, results, strategy='first_match')
        print(f'\n  ✨ 生成答案: {pred}')

        # 如果该问题在 QA 集中，显示标准答案
        for item in qa_data:
            if normalize_text(item['question']) == normalize_text(query):
                print(f'  ✅ 标准答案: {item["answer"]}')
                f1 = char_f1_score(pred, item['answer'])
                print(f'  📊 Char F1: {f1:.4f}')
                break


# ──────────────────────────────────────────────────────────────────
# 11. 主流程
# ──────────────────────────────────────────────────────────────────

def main():
    """完整 RAG 评估流水线"""

    print('\n' + '█'*60)
    print('   RAG Pipeline: FAISS + BM25 混合检索 + 自动评估')
    print('█'*60 + '\n')

    # ── 0. 路径 ──
    import os
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    corpus_path = os.path.join(base_dir, 'dinosaur_baike.txt')
    qa_path = os.path.join(base_dir, 'dataset.json')

    # ── 1. 加载数据 ──
    print('\n【1/6】加载数据')
    corpus = load_corpus(corpus_path)
    qa_data = load_qa(qa_path)

    # ── 2. 分块 ──
    print('\n【2/6】知识库分块')
    chunks = chunk_text_by_sections(corpus, max_chunk_size=256, overlap=24)
    print(f'   生成 {len(chunks)} 个文档块')

    # ── 3. 构建索引 ──
    print('\n【3/6】构建双路索引 (FAISS + BM25)')
    retriever = HybridRetriever(
        embedding_model_name='distiluse-base-multilingual-cased-v2',
        alpha=0.5,
        use_jieba=True,
    )
    retriever.build_index(chunks)

    # ── 4. 检索评估 ──
    print('\n【4/6】检索效果评估')
    ret_result = evaluate_retrieval(retriever, qa_data, k_list=[1, 3, 5])
    print_retrieval_report(ret_result)

    # ── 5. 答案评估 ──
    print('\n【5/6】答案生成评估')
    ans_result = evaluate_answer(retriever, qa_data, retrieval_k=5)
    print_answer_report(ans_result)

    # ── 6. 消融实验 + α 调优 ──
    print('\n【6/6】消融实验 & α 参数调优')
    ablation_results = run_ablation(retriever, qa_data)
    best_alpha, tune_results = tune_alpha(retriever, qa_data)

    # ── 总结 ──
    print(f'\n{"★"*60}')
    print(f'  汇总')
    print(f'{"★"*60}')
    print(f'  知识库规模: {len(corpus)} 字符 → {len(chunks)} 个 chunks')
    print(f'  测试集:     {len(qa_data)} 个问答对')
    print(f'  检索 Recall@5: {ret_result.recall_at_5:.4f}')
    print(f'  答案 Char F1:  {ans_result.char_f1:.4f}')
    print(f'  最佳 α:        {best_alpha:.1f}')
    print(f'{"★"*60}\n')

    return retriever, ret_result, ans_result


if __name__ == '__main__':
    main()