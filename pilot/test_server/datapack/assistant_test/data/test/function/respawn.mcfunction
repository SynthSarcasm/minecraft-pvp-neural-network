# kill and immediately respawn the bot Alfa at the same spot (like a player with instant respawn)
execute at Alfa run summon marker ~ ~ ~ {Tags:["odrodz"]}
player Alfa kill
execute at @e[tag=odrodz,limit=1] run player Alfa spawn at ~ ~ ~
kill @e[tag=odrodz]
