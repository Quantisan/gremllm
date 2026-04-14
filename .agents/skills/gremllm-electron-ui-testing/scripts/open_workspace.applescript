on waitForOpenDialog(appName, attemptCount)
	tell application "System Events"
		repeat attemptCount times
			try
				if exists sheet 1 of window 1 of process appName then return true
			end try
			try
				if exists window "Open Workspace Folder" of process appName then return true
			end try
			delay 0.25
		end repeat
	end tell
	error "Timed out waiting for Open Workspace Folder dialog."
end waitForOpenDialog

on waitForGoToFolder(appName, attemptCount)
	tell application "System Events"
		repeat attemptCount times
			try
				if exists window "Go to the folder" of process appName then return true
			end try
			try
				if exists text field 1 of sheet 1 of sheet 1 of window 1 of process appName then return true
			end try
			delay 0.25
		end repeat
	end tell
	error "Timed out waiting for Go to the folder sheet."
end waitForGoToFolder

on run argv
	if (count of argv) is less than 1 then error "open_workspace.applescript requires a workspace path argument."

	set workspacePath to POSIX path of (POSIX file (item 1 of argv))
	set appName to "Electron"
	if (count of argv) > 1 then set appName to item 2 of argv

	try
		tell application appName to activate
		tell application "System Events"
			if not (exists process appName) then error "Process " & appName & " is not running."
			tell process appName
				set frontmost to true
				keystroke "o" using command down
			end tell
		end tell

		my waitForOpenDialog(appName, 40)

		tell application "System Events"
			tell process appName
				keystroke "g" using {command down, shift down}
			end tell
		end tell

		my waitForGoToFolder(appName, 20)

		tell application "System Events"
			tell process appName
				keystroke workspacePath
				key code 36
				delay 0.2
				key code 36
			end tell
		end tell
	on error errMsg number errNum
		error "open_workspace.applescript failed for " & workspacePath & ": " & errMsg number errNum
	end try
end run
