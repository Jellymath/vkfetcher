# vkfetcher

### Features

This Telegram bot helps you to redirect the walls from vk.com to Telegram.

Supports:

- [x] Group, public walls
- [x] User walls
- [x] Partial support of multimedia content*
- [x] Markdown links to clickable http-links transformation
- [x] Pinned messages

*Images and videos are transformed to the links to them. Music is transformed to "Audio: artist - title" string.


### Getting started

- Obtain vk application API token: https://vk.com/apps?act=manage
- Obtain Telegram Bot API token: Ask [@BotFather](https://t.me/BotFather)
- Pass to JVM options path to the config file:

```java
vk.appId= //Application ID

vk.token= //Service token

telegram.token= //Bot API token

telegram.name= //Bot name
```

### Usage

Supported commands:

`/add wallId`

`/remove wallId`

`/list`

Use `/list` to get your current subscriptions.

wallId can be either number or text ( `/remove 1` or `/remove durov`)

wallId for public or group wall as number should be passed with `-` sign (`/add -69781078`). Nothing special for the text name.
