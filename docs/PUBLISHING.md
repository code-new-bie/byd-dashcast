# GitHub发布前清单

1.0/code33为Kotlin产品候选，实际四页界面、持续同连接可续背景和软件带线切换；具体范围见[产品说明](PRODUCT_1.0.md)。维护已授权源码分支不等于创建Release/tag或上传APK/固件。本次FULL仍含字节未改MCU/OS/引导，App相对0.10仅HMI/receiver/USB等待脚本改动。新实车物理合成、全部告警/道路和带线电气兼容未认证；只公开匹配版本/回退/真实性边界，不把CI或模拟写为实车。以下为历史发布记录。

当前源码0.10.0/code32，为五秒实际全屏视频/背景联动候选，不是仅背景检查。正常维护源码分支，不自动创建Release/tag或上传配套包。FULL含字节未改的国内MCU/参考OS，App相对0.9.1仅receiver/alpha cfg改动，HMI可执行/启动保持。物理混合、全部告警、实车FPS/长时未验收，见[TASKS](TASKS_0.10.0.md)。以下保留历史发布记录。

当前源码为0.9.1限时背景PoC，首次真实改HMI；新包仅停车/台架首测，未实车、非全屏。原公开Release不自动替换，新固件/APK不随源码提交。若另行发布，必须配套、说明HMI及原OS/MCU实际刷写范围和保留回退，不能将主机测试/CI当安全验收。

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

当前0.9.0 F1必须配套新仪表App，完整两文件包保留在内部交付目录，公开源码不含固件/APK。原OS/MCU/HMI/USB保持，只有只读扩展；全屏未开放。已有0.8.2短测实显不认证本版或真实30FPS/动态告警。公开提供纯接收器覆盖层，不是整包制作器；旧App包装器仍固定历史0.5.0，不宣称它能直接重建0.9.0。不自动替换0.4.4 Release。
