-- ponytail: start relay with OBS, kill on OBS exit. Single-purpose.
obs = obslua

local RELAY_DIR = "C:\\Users\\Raphael\\Documents\\ChurchCamera\\obs-relay"

function is_relay_running()
  local f = io.popen('netstat -an | find "0.0.0.0:3000" /c')
  local ok = f:read('*a'); f:close()
  return tonumber(ok) and tonumber(ok) > 0
end

function script_load(settings)
  if not is_relay_running() then
    os.execute('start /min "OBS Relay" node "' .. RELAY_DIR .. '\\relay.js"')
  end
end

function script_unload()
  os.execute('taskkill /f /fi "WINDOWTITLE eq OBS Relay" >nul 2>&1')
end

function script_description()
  return "Auto-starts OBS Tally Relay on OBS launch, kills on exit."
end
