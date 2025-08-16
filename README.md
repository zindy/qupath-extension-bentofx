# QuPath extension bentofx

This repo contains an extension which integrates BentoFX docking panels into QuPath

You can follow its progress over at https://forum.image.sc/t/qupath-gui-with-dockable-panels-experimental-proof-of-concept/115582

BentoFX is available over at https://github.com/Col-E/BentoFX/

No need to download it though, it gets integrated in the extension through gradle and shadowJar.

## Build the extension

Building the extension with Gradle should be pretty easy - you don't even need to install Gradle separately, because the 
[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) will take care of that.

Open a command prompt, navigate to where the code lives, and use
```bash
gradlew shadowJar
```

The built extension should be found inside `build/libs`.
You can drag this onto QuPath to install it.
You'll be prompted to create a user directory if you don't already have one.

## License

This is just a proof of concept, you're free to use it however you like.
You can treat the contents of *this repository only* as being under [the Unlicense](https://unlicense.org) (except for the Gradle wrapper, which has its own license included).

If you use it to create a new QuPath extension, I'd strongly encourage you to select a suitable open-source license for the extension.

Note that *QuPath itself* is available under the GPL, so you do have to abide by those terms: see https://github.com/qupath/qupath for more.
