on run argv
	set appName to "Electron"
	if (count of argv) > 0 then set appName to item 1 of argv

	try
		tell application appName to activate
		tell application "System Events"
			if not (exists process appName) then error "Process " & appName & " is not running."
			tell process appName
				set frontmost to true
			end tell
		end tell
	on error errMsg number errNum
		error "focus_app.applescript failed for " & appName & ": " & errMsg number errNum
	end try
end run
