# F-Droid submission

`io.github.kesh2k.keshcar.yml` is the metadata file for [fdroiddata](https://gitlab.com/fdroid/fdroiddata).
Store texts, icon and screenshots live in `fastlane/metadata/android/` (read by F-Droid automatically).

To submit: fork fdroiddata on GitLab, add this file as `metadata/io.github.kesh2k.keshcar.yml`, run
`fdroid lint io.github.kesh2k.keshcar` and `fdroid build -v -l io.github.kesh2k.keshcar` (optional), and open a merge request
using the "App inclusion" template. The tag in `commit:` must point to a commit containing the Gradle wrapper.
