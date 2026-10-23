@echo off
rem Double-click: starts the lab dashboard and opens it. Close this window to stop
rem the dashboard; the servers it started keep running.
cd /d "%~dp0"
start "" http://127.0.0.1:8765/
python dashboard.py
pause
