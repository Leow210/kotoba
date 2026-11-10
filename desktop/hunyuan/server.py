#!/usr/bin/env python3
"""HunyuanOCR as a long-running helper for Kotoba (Mac, Korean speech bubbles).
One JSON object per line on stdin: {"png": "<base64>", "lang": "ko"}; one line back: {"text": "..."} or {"error": "..."}.
Prints {"ready": true} once the model is loaded. Needs torch, transformers>=5.13 and pillow, and the tencent/HunyuanOCR
weights (HF_HOME points at where they are kept)."""
import base64, io, json, re, sys, time
import torch
from PIL import Image
from transformers import AutoProcessor, HunYuanVLForConditionalGeneration

MODEL = "tencent/HunyuanOCR"
# Telling it the script keeps one- and two-letter bubbles from coming out as Chinese, Arabic or Latin letters.
PROMPTS = {
    "ko": "이 이미지의 텍스트는 한국어(한글)입니다. 보이는 한국어 텍스트만 그대로 출력하세요. 설명은 쓰지 마세요.",
    "ja": "この画像の文字は日本語です。見える日本語の文字だけをそのまま出力してください。説明は不要です。",
}
PREAMBLE = re.compile(r"^图片中的文本内容是[:：]\s*")

dev = "mps" if torch.backends.mps.is_available() else "cpu"
proc = AutoProcessor.from_pretrained(MODEL, use_fast=False)
model = HunYuanVLForConditionalGeneration.from_pretrained(MODEL, torch_dtype=torch.bfloat16).to(dev).eval()
print(json.dumps({"ready": True}), flush=True)

for line in sys.stdin:
    try:
        req = json.loads(line)
        image = Image.open(io.BytesIO(base64.b64decode(req["png"]))).convert("RGB")
        prompt = PROMPTS.get(req.get("lang", "ko"), PROMPTS["ko"])
        msgs = [{"role": "user", "content": [{"type": "image", "image": image}, {"type": "text", "text": prompt}]}]
        inp = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True, return_dict=True, return_tensors="pt").to(dev)
        with torch.inference_mode():
            out = model.generate(**inp, max_new_tokens=200, do_sample=False, repetition_penalty=1.08)
        text = proc.batch_decode(out[:, inp["input_ids"].shape[1]:], skip_special_tokens=True)[0].strip()
        print(json.dumps({"text": PREAMBLE.sub("", text)}, ensure_ascii=False), flush=True)
    except Exception as e:
        print(json.dumps({"error": str(e)}), flush=True)
