@echo off
rem Double-click to rebuild and restart the TouchGrass agent (asks for admin).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-agent.ps1"
