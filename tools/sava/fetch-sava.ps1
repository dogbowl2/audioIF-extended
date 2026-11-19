<#
.SYNOPSIS
  Exports Inworld's SAVA end-of-turn model for the app's "Smart end of speech" setting.

.DESCRIPTION
  Creates a Python environment in tools/sava/.venv, downloads SAVA's weights
  (Apache 2.0, huggingface.co/inworld/sava), exports the one-frame streaming step to
  ONNX with export_sava_onnx.py and copies the int8 model (about 27 MB) to
  audioif/src/main/assets/sava/. That folder is git-ignored because of its size.
  Rebuild the app afterwards so the model is packaged into the APK.

  Needs Python 3.10+ on PATH. PyTorch comes from the CPU wheel index, which also has
  Windows on ARM builds.
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$venv = Join-Path $here '.venv'
$python = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path $python)) {
    python -m venv $venv
}
& $python -m pip install --quiet --upgrade pip
& $python -m pip install --quiet torch --index-url https://download.pytorch.org/whl/cpu
& $python -m pip install --quiet huggingface-hub safetensors soundfile numpy onnx onnxruntime onnxscript
# SAVA only uses soxr to resample input files, which the export doesn't need. It has no
# wheel for every platform, so a stand-in is used when it won't install.
& $python -m pip install --quiet --no-deps inworld-sava
& $python -m pip install --quiet soxr 2>$null
& $python -c "import soxr" 2>$null
if ($LASTEXITCODE -ne 0) {
    $site = & $python -c "import sysconfig; print(sysconfig.get_paths()['purelib'])"
    Set-Content -Path (Join-Path $site 'soxr.py') -Value @'
def resample(x, in_rate, out_rate, quality=None):
    if in_rate != out_rate:
        raise RuntimeError("soxr is not installed; resampling is unavailable")
    return x
'@
}
$build = Join-Path $here 'build'
& $python (Join-Path $here 'export_sava_onnx.py') --out $build
if ($LASTEXITCODE -ne 0) { throw "export failed" }
$dest = Join-Path $root 'audioif\src\main\assets\sava'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $build 'sava_step_int8.onnx') $dest -Force
Write-Host "Saved $dest\sava_step_int8.onnx"
