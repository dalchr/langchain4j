sudo docker run -e HF_TOKEN=$HF_TOKEN --gpus all \
--name vllm-gpt-oss-20b -d -p 8000:8000 \
  --ipc=host \
  vllm/vllm-openai:v0.31.0 \
  --model openai/gpt-oss-20b --max-model-len 65536 --tool-call-parser openai --reasoning-parser openai_gptoss --enable-auto-tool-choice
