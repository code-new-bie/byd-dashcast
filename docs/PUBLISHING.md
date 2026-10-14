# GitHub发布前清单

只提交这个独立源码目录，不在原研究工作区执行`git add .`。原工作区包含大体积原件、旧凭据、签名缓存和实车资料；`.gitignore`不等于可以跳过审核。

1. 确认[许可证状态](../LICENSE_STATUS.md)，由项目所有者决定LICENSE与权利声明；不将原厂/参考固件自动包含在项目许可证中。
2. `python tools/check_public_tree.py --verify-snapshot`；检查SOURCE_MANIFEST与`git status --short`，确认没有额外内容/软链接。
3. `git add .`后再次`python tools/check_public_tree.py`，以及`git diff --cached --stat`和`git diff --cached --check`。检查器会检查被强制加入索引的忽略文件，不能靠`git add -f`绕过范围。
4. 当前仓库为code-new-bie/byd-dashcast，维护2021-song-plus-dmi分支；使用WSL git/gh正常提交推送，不force覆盖其他历史。任务/使用/验证文档与代码一起维护；完成后回读提交及CI，不把云端构建当实车验收。

## 不上传什么

原厂/参考ZIP及SV、MLO/IFS/系统镜像、提取的ELF/DLL/字体/HMI资源、原始日志/照片/视频、历史激活凭据、签名与ADB身份、SDK和缓存、含个人绝对路径的工作记录。

## Release边界

公开源码与可刷固件不是同一件事。经项目所有者授权分享的0.4.4配套包已在[Release](https://github.com/code-new-bie/byd-dashcast/releases/tag/v0.4.4-2021-song-plus-dmi)独立提供，FULL含原版国内MCU原件；这些负载不放进Git源代码历史，也不自动纳入源码许可证。CI不自动发布二进制。

若以后发布APK，需要明确debug/release签名、是否能覆盖原版本、版本匹配及测试范围。签名密钥保留在仓库外，不能把原设备身份或旧密钥放进示例配置。

当前0.7.8高清首测APK必须匹配0.7.8高清仪表接收端；完整两文件包只在内部交付目录保存，公开仓库不含固件或APK。0.5.0已有短持续/后台/停止/Host现场通过，但高清实显、真实30 FPS、完整30分钟和动态告警隔离未完成。维护源码及本地两文件验证包，不自动替换0.4.4 Release。以后发布需单独核对版本/同签名APK、固件来源、适用范围和恢复前提，不将停稳候选宣传为道路产品。
