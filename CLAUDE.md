# AnyFile Opener 项目约定

## Git

- 直接在 `main` 上提交，不建分支、不开 PR。
- 需要验证的改动提交后推送到 `origin/main`，再按下文触发 CI。

## 构建与验证

本地没有 Android SDK，不要在本地运行 `./gradlew`（编译、单元测试、lint 都跑不了），也不要让用户在本地编译。验证一律走 GitHub Actions：

1. 推送到 `origin/main`。
2. 手动触发 `Build Android` 工作流，必须传 `create_release=false`：

   ```bash
   gh workflow run build-android.yml --ref main -f version=ci-<短SHA> -f create_release=false
   ```

3. 找到对应的 run（核对 `headSha` 是刚推送的提交），等待结果：

   ```bash
   gh run list --workflow build-android.yml --limit 1 --json databaseId,headSha,status,conclusion
   gh run watch <run-id> --exit-status
   gh run view <run-id> --log-failed
   ```

说明：

- 工作流执行 `testDebugUnitTest lintDebug` 和 APK 打包。仓库已配置签名 secrets，所以产物是签名的 release APK。
- `create_release` 默认为 `true`，漏传会创建或覆盖 GitHub Release 和 tag。
