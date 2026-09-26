' Launches the TouchGrass laptop agent with NO visible console window.
' Used by the TouchGrassAgent scheduled task so there is no window to close.
' It still runs in the interactive session, so input/active-window tracking works.
Dim shell, fso, exe
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
' This script lives in desktop\scripts\ ; the exe is in desktop\target\release\.
exe = fso.GetParentFolderName(fso.GetParentFolderName(WScript.ScriptFullName)) & "\target\release\touchgrass-agent.exe"
' 0 = hidden window, True = wait (keeps the task instance alive so it represents the agent).
shell.Run """" & exe & """ run", 0, True
