package com.qcenaman.remoteai.net

import java.util.Base64

/**
 * PowerShell that ends every local-model runner on the PC, however it was started
 * (LM Studio app, lms CLI or its daemon, llama.cpp servers, Ollama, KoboldCpp, llamafile),
 * with their child processes, then reports what was killed and how much GPU memory is left in use.
 * Python is never matched, so ComfyUI and similar tools keep running.
 */
val KILL_SCRIPT: String = """
§ErrorActionPreference = 'SilentlyContinue'
§names = '^(lm studio|lms|lmstudio.*|llmster.*|llmworker.*|llama|llama-.*|llamafile.*|ollama.*|koboldcpp.*|tabbyapi.*)§'
§dirs = '\\\.lmstudio\\|\\LM Studio\\|\\lm-studio\\|\\Ollama\\'
function Find-Ai { @(Get-Process | Where-Object { §_.Id -ne §PID -and (§_.ProcessName -match §names -or (§_.Path -and §_.Path -match §dirs)) }) }
§hit = Find-Ai
if (§hit.Count -eq 0) { 'Nothing was running.' } else {
  §hit | Group-Object ProcessName | ForEach-Object { 'Killed ' + §_.Name + ' x' + §_.Count }
  §hit | ForEach-Object { taskkill /F /T /PID §_.Id 2>&1 | Out-Null }
  Start-Sleep -Seconds 2
  §left = Find-Ai
  if (§left.Count) { 'Still running: ' + ((§left | Select-Object -ExpandProperty ProcessName -Unique) -join ', ') }
  else { 'All local AI processes stopped.' }
}
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
  'GPU memory in use: ' + ((nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader) -join '; ')
}
""".trimIndent().replace('§', '$')

/** Wraps a PowerShell script so it survives any shell's quoting: powershell -EncodedCommand (UTF-16LE base64). */
fun encodedPowerShell(script: String): String =
    "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand " +
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

/** The command the Kill button runs on the PC. */
fun killCommand(): String = encodedPowerShell(KILL_SCRIPT)
