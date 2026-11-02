# 发布检查

本仓库只放源码和文档。固件原件、完整刷机包、APK、签名密钥、ADB 身份、车辆照片和日志都不提交。

## 提交前

1. 运行[构建文档](BUILD.md)“提交前检查”一节里的四条命令，全部通过。
2. 新增或修改文件后，更新 `SOURCE_MANIFEST.json`，确认清单与实际文件一致：
   ```bash
   python tools/check_public_tree.py --refresh-snapshot   # 只在人工审阅过新增文件之后运行
   python tools/check_public_tree.py --verify-snapshot
   ```
3. 用 `git status` 和 `git diff` 逐项审阅改动，确认没有多余文件或软链接。新增 PNG 必须登记到 `check_public_tree.py` 的 `OWNED_BACKGROUNDS`，否则检查会拒绝。
4. 推送后看 CI 结果，以本次提交的运行为准。

## 约定

- Release 只在项目所有者明确要求时创建，一律标为 Pre-release，附件写明 SHA256。现有两个：`v0.4.4-2021-song-plus-dmi`（0.4.4 完整仪表包与 APK）和 `receiver-1.2.0-2021-song-plus-dmi`（构建用的 1.2.0 接收端）；
- 普通提交不附带 APK 或固件；
- 不 force push；
- 不把 CI 或模拟器结果写成实车结论。

## 许可证

项目整体许可证尚未选择，见 [LICENSE_STATUS](../LICENSE_STATUS.md)。第三方声明见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。
