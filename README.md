<div align="center">
<img alt="icon-512.png" height="80" src="ani-rss-ui/public/icon-512.png"/>
<h1 align="center" style="margin-top: 0">ANI-RSS OpenList</h1>
<p align="center">
<strong>基于RSS自动追番、订阅、下载、刮削、洗版</strong>
</p>

[上游快速开始](https://docs.wushuo.top/start)
|
[上游使用文档](https://docs.wushuo.top/add-rss)
|
[上游 Docker 部署](https://docs.wushuo.top/deploy/docker)
|
[常见问题](https://docs.wushuo.top/faq)
|
[参与开发](https://docs.wushuo.top/dev/basic)

[![GitHub](https://img.shields.io/badge/-GitHub-181717?logo=github)](https://github.com/KKKKeybird/ani-rss-openlist)
![GitHub License](https://img.shields.io/github/license/KKKKeybird/ani-rss-openlist)
[![OpenList compatibility check](https://github.com/KKKKeybird/ani-rss-openlist/actions/workflows/openlist-check.yml/badge.svg)](https://github.com/KKKKeybird/ani-rss-openlist/actions/workflows/openlist-check.yml)
[![telegram](https://img.shields.io/static/v1?label=telegram&amp;message=ani-rss&amp;color=blue)](https://t.me/ani_rss)

</div>

项目结构和下载流程见 [项目梳理](docs/project-overview.md)。

## 关于这个 fork

这是基于 [ANI-RSS 上游项目](https://github.com/wushuo894/ani-rss) 的社区维护 fork。这里的 `main` 持续维护原生 OpenList 下载器，供需要 OpenList 的用户跟踪和贡献；它不是上游官方发布版本。

当前基于上游 `v3.2.43`，支持 OpenList 合集下载（沿用合集预览、匹配/排除规则、集数偏移及重命名模板），以及 Ani-RSS 创建的 OpenList 任务列表、进度和标签持久化，云端重命名与移动、完成通知、删除及保存路径调整。接口按 [OpenList v4.2.6 官方文档](https://doc.oplist.org/api/apidocs)核对，具体改动见 [兼容路线与实现记录](docs/openlist-compat-evaluation.md)。维护者已持续在实际 OpenList 环境中测试，确认可正常使用。

手动运行 `build` 工作流并通过检查后会生成 [GitHub Release](https://github.com/KKKKeybird/ani-rss-openlist/releases) 并构建多架构 Docker 镜像。镜像发布到 `ghcr.io/kkkkeybird/ani-rss-openlist:latest`，OpenJ9 变体使用 `:openj9`；版本标签随每次发布生成。这个 fork 不会覆盖上游的 Docker 镜像。默认 Release 标签跟随上游版本并加 `-openlist`，例如 `v3.2.43-openlist`。按维护者要求发布修订版时，可手动指定 `release_revision=r1`，生成 `v3.2.43-openlist-r1`，应用版本仍为 `3.2.43`；不自动生成修订号或构建计数。每次发布同时更新 `latest` 和 `openj9` 滚动标签。默认不覆盖已有版本标签；明确要求更新同一版本时，可手动运行 `build` 并开启 `replace_existing_release`，在构建和镜像推送成功后更新同名标签及 Release 附件。

仓库保留 `build.yml` 手动发布镜像和 Release，以及 `openlist-check.yml` 执行回归测试和完整构建。

**上游自动同步已关闭。** 不再定时跟随上游、自动创建同步 PR 或自动合并。仅在仓库所有者明确要求时手动获取选定的上游正式版本，优先保留 OpenList 功能，修复冲突并验证合集等回归后再合并。云端维护定时任务也已暂停，不会因空轮询消耗 Codex 额度。

OpenList 没有通用的做种比率、上传限速与全局 Tracker API；这些 qBittorrent 功能无法在 OpenList 后端等价实现。上游文档适用于通用功能，OpenList 的差异以本仓库记录为准。

### OpenList 合集下载

在设置中选择 OpenList、配置地址、令牌和 Driver 后，从订阅页「添加合集」上传种子，先预览再开始。任务提交后返回，进度与错误在下载列表查看；离线任务完成后按预览逐文件重命名、移动，并确认目标文件名及大小后标记完成。文件映射与整理计划持久化，服务重启后可继续。

OpenList 通用离线 API 不支持 qBittorrent 的种子内文件优先级，因此会下载完整种子，匹配/排除只影响最终归档。被排除的文件留在下载目录下的 `.ani-rss-openlist-*` 暂存目录供手动处理。空预览、重复目标名、目标已存在、源文件缺失或路径不唯一时拒绝操作，避免覆盖和错配。合集采用预览中的文件名，与 qBittorrent 合集行为一致。

## 上游项目说明（保留）

![image](https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/screenshot/screenshot.webp#gh-light-mode-only)
![image](https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/screenshot/screenshot-dark.webp#gh-dark-mode-only)

## 其他

[关于不接受“纯 AI 生成”的 Pull Request 的说明](https://github.com/wushuo894/ani-rss/discussions/685)

### 推广须知

请不要在 **B站** 或 **中国大陆社交平台** 发布 视频/文章 宣传本项目

如确实有需要请尽量使用简称: **ASS**

### 相关文章

- [猫猫博客 Docker 部署 ani-rss 实现自动追番](https://catcat.blog/docker-ani-rss.html)

- [从零开始的NAS生活 第四回：ANI-RSS，自动追番！](https://www.wtsss.fun/archives/qhaQ3M7v)

- [自动化追番计划](http://jinghuashang.cn/posts/8f622332.html)

- [ANI-RSS：自动追番新姿势！](https://www.himiku.com/archives/ani-rss.html)

- [📺 彻底解放双手！2025年最新 Ani-RSS + qBittorrent 全自动追番保姆级教程](http://www.nuan1145.eu.cc/archives/wei-ming-ming-wen-zhang-75sNBWk0)

### 贡献者

<a href="https://github.com/wushuo894/ani-rss/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=wushuo894/ani-rss" alt="contributors" />
</a>

## 爱发电

<a href="https://ifdian.net/a/wushuo894" target="_blank">
  <img src="https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/support_ifdian.svg" alt="support_ifdian">
</a>

您的每一次 star ⭐ 和 赞助 🎁 都是我持续优化的动力。让我们一起维护这个用爱发电的项目！

## 免责声明

### 项目性质

本工具为中立性技术辅助工具，通过自动化程序抓取互联网公开分享的种子文件链接 (非存储内容)，并向用户指定的下载工具 (如
qBittorrent、Transmission、Aria2 等)推送任务指令。工具本身不具备资源存储、分发及内容审查功能

### 用户责任

- 合法性承诺：用户需确保下载行为及文件使用符合所在国家/地区的《著作权法》《网络安全法》等法规，禁止用于盗版、非法传播等用途
- 自担风险：种子文件的合法性、安全性（如病毒、违规内容）由资源提供方独立负责，用户需自行验证并承担由此引发的法律与经济风险

### 开发者免责

- 技术中立性：开发者仅维护工具的功能实现，不参与种子文件的内容控制、编辑或优化，亦无法保证链接有效性、完整性与获取速率
- 免责范围
    - 用户因使用第三方种子导致的设备损害、数据丢失或法律纠纷
    - 因网络政策、技术更新或源站限制造成的服务中断或功能失效
- 例外追责
    - 若监管机构认定本工具违背技术中立原则，开发者保留终止服务的权利

## Sponsors

<img src="https://docs.wushuo.top/assets/sharon-networks.5DoCcrXN.webp" width="300" alt="Sharon Networks"/>

### 🚀 Sponsored by SharonNetworks

本项目的构建与发布环境由 SharonNetworks 提供支持 —— 专注亚太顶级回国优化线路，高带宽、低延迟直连中国大陆，内置强大高防 DDoS
清洗能力。

SharonNetworks 为您的业务起飞保驾护航！

#### ✨ 服务优势

* 亚太三网回程优化直连中国大陆，下载快到飞起
* 超大带宽 + 抗攻击清洗服务，保障业务安全稳定
* 多节点覆盖（香港、新加坡、日本、台湾、韩国）
* 高防护力、高速网络；港/日/新 CDN 即将上线

想体验同款构建环境？欢迎 [访问 Sharon 官网](https://sharon.io) 或 [加入 Telegram 群组](https://t.me/SharonNetwork)
了解更多并申请赞助。

[<img alt="image" src="https://github.com/wushuo894/ani-rss-docs/raw/main/docs/image/support.nodeget.com_page_promotion_id%3D88.webp" width="300"/>](https://yxvm.com/)

[NodeSupport](https://github.com/NodeSeekDev/NodeSupport) 赞助了本项目

[![Powered by DartNode](https://dartnode.com/branding/DN-Open-Source-sm.png)](https://dartnode.com "Powered by DartNode - Free VPS for Open Source")

