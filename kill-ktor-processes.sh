ps aux | grep netty | grep "ktor" | awk {'print $2'} | xargs kill
