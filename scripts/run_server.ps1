$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
pip install -q fastapi uvicorn pydantic requests pynacl 2>$null
python -m uvicorn server.main:app --host 0.0.0.0 --port 8765