<div align="center">

# SmoothCursor

**Плавный курсор как в Neovide, неоновое свечение, плавное появление текста и свои обои для Termux**

[🇬🇧 English](README.md) · [🇷🇺 Русский](README.ru.md)

[![Скачать](https://img.shields.io/badge/Скачать-последний%20APK-brightgreen?style=for-the-badge)](https://github.com/AstrodiR2/SmoothCursor/releases/latest)
[![LSPosed](https://img.shields.io/badge/Нужен-LSPosed-blue?style=for-the-badge)](https://github.com/LSPosed/LSPosed)

</div>

SmoothCursor это модуль [LSPosed](https://github.com/LSPosed/LSPosed) для [Termux](https://f-droid.org/packages/com.termux/). Он подключается к отрисовке терминала и добавляет эффекты, знакомые по [Neovide](https://neovide.dev/). Всё настраивается в отдельном приложении.

## Демо

| Плавный курсор | Свечение курсора и текста |
|:---:|:---:|
| [![Плавный курсор](docs/media/01-smooth-cursor.gif)](docs/media/01-smooth-cursor.mp4) | [![Свечение](docs/media/02-glow.gif)](docs/media/02-glow.mp4) |
| **Плавное появление текста** | **Свои обои** |
| [![Появление текста](docs/media/03-fade-in.gif)](docs/media/03-fade-in.mp4) | [![Обои](docs/media/04-wallpaper.gif)](docs/media/04-wallpaper.mp4) |

Нажми на GIF, чтобы открыть видео в полном качестве.

## Возможности

- **Плавный курсор**: скользит между позициями, есть шлейф, как в Neovide
- **Неоновое свечение**: текст и курсор светятся цветом самого текста, размер и яркость настраиваются
- **Плавное появление текста**: новые символы (ввод, `ls`, вывод команд) проявляются плавно, длительность настраивается, можно выключить
- **Картинки в терминале**: настоящие картинки от `fastfetch`, `imgcat` и своих скриптов (протоколы iTerm2 и kitty), слои за текстом и перед текстом
- **Плавная прокрутка** с настраиваемой скоростью
- **Отступы** внутри окна терминала
- **Обои**: картинка, видео или градиент под текстом, с затемнением
- **Заставка при запуске**: по желанию, со звуком
- **Приложение настроек**: всё включается и настраивается на лету, для большинства опций перезапуск Termux не нужен

## Картинки в терминале

Модуль понимает два графических протокола, поэтому программы могут рисовать настоящие картинки прямо в Termux:

- **iTerm2** (`OSC 1337;File=`): `imgcat`, `fastfetch --logo-type iterm`
- **kitty** (`APC G`, подмножество): `fastfetch --logo-type kitty-direct`, свои скрипты

Точные ключи логотипа смотри в `fastfetch --help` для своей версии. Быстрая проверка руками:

```bash
# перед текстом
printf '\033]1337;File=inline=1;width=20:%s\a' "$(base64 -w0 image.png)"
# за текстом (z=-1 это расширение, другие терминалы его игнорируют)
printf '\033]1337;File=inline=1;width=20;z=-1:%s\a' "$(base64 -w0 image.png)"
# протокол kitty, за текстом
printf '\033_Ga=T,f=100,z=-1;%s\033\\' "$(base64 -w0 image.png)"
```

Картинки прокручиваются вместе с текстом, исчезают, когда ячейки под ними стёрты (`clear`), и хранятся отдельно для каждой сессии Termux. Переключатель «Рисовать картинки за текстом» в приложении настроек задаёт слой по умолчанию для программ, которые его не выбрали. Слои: `z >= 0` перед текстом, `z < 0` за текстом (ячейки с цветным фоном всё равно закрывают картинку).

Поддерживаемые ключи kitty: `a=T|t|p|d|q`, `f=24|32|100`, `s`, `v`, `t=d|f|t`, `o=z`, `m`, `i`, `c`, `r`, `z`, `C`, `q`, `d=a|A|i|I`. Не поддерживается: обрезка (`x y w h`), смещения в пикселях, юникод-плейсхолдеры, shared memory, анимация, sixel. Внутри `tmux` картинки проходят только с включённым passthrough.


## Требования

- Android 7.0+ (API 24+) с root и [LSPosed](https://github.com/LSPosed/LSPosed)
- [Termux из F-Droid](https://f-droid.org/packages/com.termux/) (версия из Google Play устарела)
- Для свечения нужен Android 9+

## Установка

1. Скачай `SmoothCursor.apk` из [последнего релиза](https://github.com/AstrodiR2/SmoothCursor/releases/latest) и установи.
2. Открой **LSPosed → Модули → SmoothCursor**, включи модуль и выбери **Termux** в области действия.
3. Останови Termux принудительно и запусти заново.
4. Открой приложение **SmoothCursor** и настрой эффекты.

## Сборка из исходников

Каждый пуш в `main` собирает APK через GitHub Actions и выкладывает его в [Releases](https://github.com/AstrodiR2/SmoothCursor/releases). Локально:

```bash
gradle assembleRelease
```

## Заметки

- Модуль затрагивает только процесс `com.termux`.
- Если эффект не появился, посмотри лог LSPosed, строки начинаются с `SmoothCursor`.

## Участие в проекте

Issues и pull request'ы приветствуются: [создать issue](https://github.com/AstrodiR2/SmoothCursor/issues).
