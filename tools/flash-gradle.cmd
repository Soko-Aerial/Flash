@echo off
rem Same as flash-gradle.ps1 for CLIs that run cmd or Git Bash instead of PowerShell (OpenCode, Antigravity terminals).
rem Usage from your own worktree root:  tools\flash-gradle.cmd :core:enterprise:jvmTest
rem Docs: docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0flash-gradle.ps1" %*
exit /b %ERRORLEVEL%
