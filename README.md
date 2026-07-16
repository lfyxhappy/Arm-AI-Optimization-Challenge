---
type: project
status: active
created: 2026-07-16
tags:
  - hackathon
  - arm64
  - mobile-ai
  - llm
---

# Arm AI Optimization Challenge

一加 13 本地大语言模型优化参赛项目。

## 赛事

- 赛事主页：https://arm-ai-optimization-challenge.devpost.com/
- 目标设备：OnePlus 13，24GB RAM
- 初始模型候选：Qwen3-4B、Qwen3-8B
- 计划方向：Mobile AI / Edge AI

## 项目目标

在一加 13 上运行中文大语言模型，比较原始模型与自行量化、推理调优后的性能差异，重点记录：

- 首 token 延迟
- tokens/s
- 峰值内存
- 模型体积
- 温度与耗电

## 目录规划

- `docs/`：方案、实验记录和 Devpost 文案
- `models/`：模型说明与下载记录，不直接提交大模型权重
- `android/`：Android 应用和端侧推理代码
- `benchmarks/`：性能测试脚本、原始数据和结果
- `assets/`：截图、演示视频相关素材

## 模型来源

- Qwen3-4B：https://huggingface.co/Qwen/Qwen3-4B
- Qwen3-8B：https://huggingface.co/Qwen/Qwen3-8B

优先使用官方未量化的 safetensors 权重，再由本项目自行转换和优化。

