"""
medbox OCR 侧车（FastAPI）。

把"图片 / PDF → 纯文本"用 FastAPI 包一层：Java 侧 OcrClient 以 multipart/form-data 的 `file`
字段 POST 到本服务的 `/ocr`（见 medbox-server 的 ai/LocalOcrClient，endpoint 默认 http://localhost:8000/ocr）。

- 图片：直接 RapidOCR 识别
- PDF：用 pymupdf 逐页渲染成图后再 OCR（文档 05：PDF 纯文字层后续可由 Java 侧 pdfbox 抽取跳过 OCR，本侧车仅兜底）
- 启动：`uvicorn main:app --host 0.0.0.0 --port 8000`（生产用 Dockerfile / docker-compose 的 ocr 服务）

本服务纯本地、不出网、无需 Key，是"本地优先"AI 方案的一环（文档 05 的 4.1）。
"""
import os
import tempfile
from typing import List

import uvicorn
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import PlainTextResponse
from rapidocr_onnxruntime import RapidOCR

app = FastAPI(title="medbox-ocr-sidecar", version="1.0.0")

_ocr: RapidOCR | None = None


def _get_ocr() -> RapidOCR:
    global _ocr
    if _ocr is None:
        _ocr = RapidOCR()
    return _ocr


def _ocr_image(path: str) -> str:
    result, _ = _get_ocr()(path)
    if not result:
        return ""
    return "\n".join(line[1] for line in result)


@app.post("/ocr", response_class=PlainTextResponse)
async def ocr_endpoint(file: UploadFile = File(...)) -> PlainTextResponse:
    data = await file.read()
    suffix = os.path.splitext(file.filename or "")[1].lower()

    # PDF：逐页渲染成图再 OCR
    if suffix == ".pdf":
        try:
            import fitz  # pymupdf
        except ImportError:
            return PlainTextResponse("PDF 需要 pymupdf：pip install pymupdf", status_code=500)
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as f:
            f.write(data)
            pdf_path = f.name
        pages: List[str] = []
        try:
            doc = fitz.open(pdf_path)
            for page in doc:
                pix = page.get_pixmap(dpi=200)
                with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as img:
                    pix.save(img.name)
                    pages.append(_ocr_image(img.name))
                    os.unlink(img.name)
        finally:
            os.unlink(pdf_path)
        return PlainTextResponse("\n".join(pages))

    # 图片：直接 OCR
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix or ".img") as f:
        f.write(data)
        path = f.name
    try:
        return PlainTextResponse(_ocr_image(path))
    finally:
        os.unlink(path)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
