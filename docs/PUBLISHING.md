# GitHub发布前清单

只提交这个独立源码目录，不在原研究工作区执行`git add .`。原工作区包含大体积原件、旧凭据、签名缓存和实车资料；`.gitignore`不等于可以跳过审核。

1. 确认[许可证状态](../LICENSE_STATUS.md)，由项目所有者决定LICENSE与权利声明；不将原厂/参考固件自动包含在项目许可证中。
2. `python tools/check_public_tree.py --verify-snapshot`；检查SOURCE_MANIFEST与`git status --short`，确认没有额外内容/软链接。
3. `git add .`后再次`python tools/check_public_tree.py`，以及`git diff --cached --stat`和`git diff --cached --check`。检查器会检查被强制加入索引的忽略文件，不能靠`git add -f`绕过范围。
4. 由操作者创建GitHub空仓库，再手动设置remote/commit/push；本地整理没有创建远程、上传文件或替操作者提交。

## 不上传什么

原厂/参考ZIP及SV、MLO/IFS/系统镜像、提取的ELF/DLL/字体/HMI资源、原始日志/照片/视频、历史激活凭据、签名与ADB身份、SDK和缓存、含个人绝对路径的工作记录。

## Release边界

公开源码与可刷固件不是同一件事。App-only升级ZIP包含原厂App内容，不随源码公开；即使使用GitHub Release而不是Git提交也应单独核对发布范围与来源权限。CI不自动发布任何二进制。

若以后发布APK，需要明确debug/release签名、是否能覆盖原版本、版本匹配及测试范围。签名密钥保留在仓库外，不能把原设备身份或旧密钥放进示例配置。

源码依赖外部匹配输入才能生成仪表App，不在README提供未知来源固件下载链接。原实测两文件交付留在本地，内容/哈希不因本轮整理而改变。
