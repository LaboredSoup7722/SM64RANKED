-- Set the correct memory domain
memory.usememorydomain("RDRAM")

local STAR_ADDRESS = 0x33B21A
local LEVEL_ADDRESS = 0x32DDF8

-- ==========================================
-- FILE PATHS (Relative to the EmuHawk.exe location)
local jsonFilePath = "data/player1.json"
local cmdFilePath  = "data/cmd1.txt"
-- ==========================================

local lastStars = -1
local lastLevel = -1
local lastCmd = ""

console.clear()
console.log("SM64 Ranked Lua Tracker Started!")

-- Helper function to read the Java command safely
function read_cmd()
    local cmd = "GO"
    local cmdFile = io.open(cmdFilePath, "r")
    if cmdFile then
        cmd = cmdFile:read("*all")
        cmdFile:close()
    end
    if cmd ~= nil then
        cmd = cmd:gsub("%s+", "")
    end
    return cmd
end

while true do
    -- 1. Read game memory
    local currentStars = memory.read_u16_be(STAR_ADDRESS)
    local currentLevel = memory.read_u16_be(LEVEL_ADDRESS)
    
    -- 2. Read Command from Java
    local cmd = read_cmd()

    -- 3. THE HARD PAUSE STARTING GATE
    if cmd == "WAIT" then
        console.log("Waiting for opponent... Pausing emulator.")
        
        -- Freeze the emulator completely
        client.pause()
        
        local yieldCount = 0
        while cmd == "WAIT" do
            -- Only check the file every 20 UI ticks to prevent file-locking crashes
            if yieldCount % 20 == 0 then
                cmd = read_cmd()
            end
            
            -- Clear the graphics buffer before drawing so it doesn't stack
            gui.clearGraphics()
            gui.drawText(10, 10, "WAITING FOR OPPONENT... PAUSED", "red", "black", 14, "Arial")
            
            -- Yield keeps Lua running in the background while the game is paused
            emu.yield()
            yieldCount = yieldCount + 1
        end
        
        -- Use clearGraphics() to completely wipe the drawing layer before resuming
        gui.clearGraphics()
        console.log("Opponent ready! Unpausing.")
        client.unpause()
    end

    -- 4. Write to JSON only if data changed
    if currentStars ~= lastStars or currentLevel ~= lastLevel then
        local file = io.open(jsonFilePath, "w")
        if file then
            local payload = string.format('{"stars": %d, "level": %d}', currentStars, currentLevel)
            file:write(payload)
            file:close()
            
            lastStars = currentStars
            lastLevel = currentLevel
            console.log("Updated State -> Stars: " .. currentStars .. " | Level ID: " .. currentLevel)
        end
    end
    
    if cmd ~= lastCmd then
        console.log("Java Command Changed -> " .. cmd)
        lastCmd = cmd
    end
    
    emu.frameadvance()
end