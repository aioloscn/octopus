local key = KEYS[1]
local maxRequests = tonumber(ARGV[1])
local timeWindow = tonumber(ARGV[2])
local banTime = tonumber(ARGV[3])
local limitKey = key .. ':lock'
local counterKey = key .. ':counter'

if redis.call('EXISTS', limitKey) == 1 then
    return 1
end

local count = redis.call('INCR', counterKey)
if count == 1 then
    redis.call('EXPIRE', counterKey, timeWindow)
end

if count > maxRequests then
    redis.call('SETEX', limitKey, banTime, '1')
    redis.call('DEL', counterKey)
    return 1
end

return 0