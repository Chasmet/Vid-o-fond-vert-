"""Fetch and verify the official model weights for the single offline release APK."""
import hashlib
import pathlib
import subprocess
import tarfile
import os

root = pathlib.Path(__file__).resolve().parents[1]
folder = root / ".gradle/thirdparty/embedded-assets/models"
folder.mkdir(parents=True, exist_ok=True)
archive = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
models = [
    (archive + ".tar.bz2", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + archive + ".tar.bz2", "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96", 878702423),
    ("UVR-MDX-NET-Voc_FT.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx", "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4", 66762795),
    ("ggml-large-v3-turbo-q5_0.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/fb5ed9369c26eaf08392504d8446da431e0fffb9/ggml-large-v3-turbo-q5_0.bin", "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574041195),
]

def valid(path, sha, size):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as fp:
        h = hashlib.sha256()
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest() == sha

for name, url, sha, size in models:
    target = folder / name
    if not valid(target, sha, size):
        part = target.with_suffix(target.suffix + ".part")
        try:
            subprocess.run(["curl","--fail","--location","--retry","3","--connect-timeout","30","--max-time","900","--output",str(part),url], check=True)
            if not valid(part, sha, size):
                raise RuntimeError("Invalid model: " + name)
            os.replace(part, target)
        finally:
            part.unlink(missing_ok=True)
    print("Verified:", name, size, flush=True)

required = {"conv_frontend.onnx","encoder.int8.onnx","decoder.int8.onnx","tokenizer/merges.txt","tokenizer/tokenizer_config.json","tokenizer/vocab.json"}
with tarfile.open(folder / (archive + ".tar.bz2"), "r|bz2") as tar:
    for member in tar:
        name = member.name.removeprefix("./").removeprefix(archive + "/")
        if member.isfile() and member.size > 0:
            required.discard(name)
        if not required:
            break
if required:
    raise RuntimeError("Qwen archive missing: " + str(required))
print("All three offline models verified.", flush=True)
