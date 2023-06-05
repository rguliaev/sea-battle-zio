Sea battle backend written in Scala using some pieces of ZIO ecosystem. 
I am coding it because my friend is learning Frontend stack and with help of this project he knows what websocket is.
Wish him good luck in becoming a good Frontend engineer !

This is demo project for learning purpose.

How to build: 
1. Docker:
- sbt docker:publishLocal
- docker run -p 80:8080 sea-battle-zio:x.x.x

2. Jar:
- sbt assembly
- java -cp target/scala-2.13/sea-battle-zio-x.x.x.jar me.guliaev.seabattle.Application