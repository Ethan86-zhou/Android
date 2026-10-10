# 果刃写实水果素材

生成日期：2026-10-11。此组素材用于游戏的完整水果与切开后的果肉，由 Codex 内置 `image_gen` 工具分别生成，共 12 次独立生成调用；未使用 CLI/API 密钥、参考图片或第三方游戏素材。每次均设置 `transparent_background=true`。

## 文件与用途

最终文件保存在 `app/src/main/assets/fruits/`。根目录六张 PNG 为完整水果，`cut/` 内六张为完整正面圆形截面。截面保留整圈果皮与果肉，左右两半由游戏渲染器裁切显示，不需要对源 PNG 进行二次编辑。

原始生成目录标识：`01a126a2-6214-7412-adbf-b4870e7953b5`。下面记录每张图片的原始生成文件名及最终文件校验值，原始生成输出保留，项目文件为直接复制。未裁剪、重采样、调色、修改透明度或转换格式。

所有 12 张图片均为 **1254 × 1254、RGBA PNG**，alpha 范围 **0–255**，透明背景像素均为 alpha 0。果实中心 alpha 为 250–253，保持工具原始的接近不透明的果肉与果皮。极淡的 alpha 1 边缘像素可能接近画布边缘，不能使用 alpha 大于 0 的包围框直接代替可见果实碰撞范围；果体、叶片与菠萝冠的显示范围可由运行时元数据单独定义。

`fruits/body-bounds.json` 记录目视核对的果实本体边界（原图坐标归一化为 0–1），排除瓜柄、苹果叶和菠萝冠。渲染器保持图片比例，将本体最大直径映射为 `2 × radius`，整果和切面预缓存为 384 像素的本体直径；源 PNG 不改动。透明留白及叶冠不会缩小果实体积。抛物线按旋转轮廓预留顶部空间，避免大果或叶冠遮挡顶部操作区域。

| 最终文件（相对 fruits/） | 原始生成文件 | 中心 alpha | SHA-256 |
| --- | --- | ---: | --- |
| `watermelon.png` | `exec-cd133afb-2ad8-43f7-8321-74f455836592.png` | 252 | `6acb23ef7ecfc30a78ffdab71e428370dfae21074680b7cc4eab0045eebfdd94` |
| `cut/watermelon.png` | `exec-9b477172-2360-49c1-a1c6-f00945bff09b.png` | 252 | `af6f09971bd6157b79ba1006a6e3e8fa2f846ce6eadc6bd46726e3fe4d8d2ab8` |
| `orange.png` | `exec-2f5c820b-94d7-468e-b1c7-af7015f7aa8c.png` | 253 | `6261641ae328370ef757a6e19c41909c3195a5c59e40e9ab327626229e2e0694` |
| `cut/orange.png` | `exec-4b2f76c4-d528-4658-954e-def1de20654f.png` | 251 | `fadfbaff6025a7919e0b36e2770f64a842e6ad41036d96857d115cb0d42d00e2` |
| `kiwi.png` | `exec-55ed4043-4e0d-4122-aeb6-6349e386021b.png` | 252 | `edc960da6c1ef337828b25455decba0ae3e0ef596737105f904d51a658f97f3b` |
| `cut/kiwi.png` | `exec-3e8bd307-2fe7-40c2-92cb-1d9be0d94d28.png` | 250 | `58f1bfde29a46e90e149df5ceb6817fa74f08bcf988fb296fbe36772786cfb5e` |
| `apple.png` | `exec-b10081c7-b5af-452f-bc98-0a56e49657c5.png` | 253 | `abbaca9d945ed128e4a7c980fe947cb77b196128606b58dec5755ed092a71729` |
| `cut/apple.png` | `exec-33116d89-74de-4c52-a937-c3fa03d9b87c.png` | 252 | `d8d989d43ff81e9663a76cadef62b9051494a6dbf9cd984ea59a69c2118c9e65` |
| `pineapple.png` | `exec-04e86b8a-7306-4e6c-9e7a-9ac53d5376e5.png` | 252 | `4157e9ecf83149679dc4de18fe0f93cc23897b5e8f4983fa024aba389494cc55` |
| `cut/pineapple.png` | `exec-d1bf5387-9245-41bc-8505-5a8468e8dc27.png` | 252 | `f3980fa36d8709ba68d271249cd1c4f414989836c2e9cf026b6b941587dce8f7` |
| `plum.png` | `exec-1bd15062-3f88-42f7-948d-8616dfa313be.png` | 252 | `c0fe356fca4223ab91014b75a9fe56bdfef8d674fa0e6860b910a6088de84969` |
| `cut/plum.png` | `exec-afd95de9-33ac-4323-ae97-7a2c9360b63e.png` | 252 | `be92870e5ef0a16160be39a80f9625004dda2296422f64da95b7f8b561684080` |

## 检查结果

已逐张目视检查生成结果：六种完整水果分别呈现西瓜条纹、橙皮凹点、猕猴桃绒毛、红苹果表皮、菠萝菱形外皮、红紫李子果粉；六种截面分别呈现红色瓜瓤和黑籽、橙瓣、猕猴桃籽圈、苹果五瓣果核、菠萝金黄纤维和李子中心果核。主体完整可见，无文字、标识、水印或地面阴影，背景为实际透明通道。

Pillow 仅用于读取 PNG 尺寸、颜色模式、alpha 统计与 SHA-256 检查，没有用于图片编辑。完整水果的菠萝冠、苹果叶片保留自然形状，程序应围绕可见果体对齐，避免将叶片或冠部计入圆形果体直径。

## 完整水果提示词

每张完整水果使用以下共同提示词，再附加对应 `Subject:` 行。生成参数：`transparent_background=true`；无输入参考图。

```text
Use case: product-mockup
Asset type: individual Android fruit slicing game sprite, square raster PNG cutout.
Style/medium: photorealistic fresh fruit studio product photography, bright naturally saturated colors, very recognizable at small game size, detailed organic surface, realistic soft upper-left studio lighting and gentle self-shading, consistent 85mm camera perspective.
Scene/backdrop: genuinely transparent alpha background, isolated floating fruit with absolutely no ground plane and no cast shadow.
Composition/framing: ONE whole uncut fruit, front view with slight three-quarter depth, centered and filling about 85 percent of square canvas, entire fruit visible with a narrow transparent margin on every side. Compact silhouette.
Constraints: real edible fruit, not illustration, not cartoon, not plastic or toy, no hands, no other objects, no sliced fruit, no text, no labels, no logo, no border, no watermark, no background, no floor shadow.
```

- **watermelon**: a whole nearly spherical green watermelon with broad dark green stripes, subtle natural peel texture and a tiny compact dry stem. Its round green body dominates the frame. It must instantly read as a watermelon.
- **orange**: a whole plump ripe orange, round orange body with realistic finely dimpled orange peel and a tiny compact green calyx at the top. No large leaves.
- **kiwi**: a whole rounded oval kiwifruit, natural brown fuzzy skin with fine short hairs and a small dry circular stem scar. Rich warm brown color, no green flesh visible.
- **apple**: a whole fresh round red apple, rich red peel with subtle red-gold variation and minute natural dots, compact short brown stem and one small green leaf close to the body. Natural juicy apple shape.
- **pineapple**: a whole ripe pineapple with a SHORT COMPACT green crown, squat near-round golden yellow body with detailed raised brown diamond lozenges and green accents. Crown fits tightly above the body, not tall or sprawling. Whole fruit silhouette nearly round enough for a game collision circle.
- **plum**: a whole fresh plump round plum, deep red-purple skin with softly mottled natural powdery bloom, a subtle seam and a tiny compact stem. Rich realistic red-purple fruit skin, no large leaves.

## 果肉截面提示词

每张截面使用以下共同提示词，再附加对应 `Subject:` 行。生成参数：`transparent_background=true`；无输入参考图。

```text
Use case: product-mockup
Asset type: individual Android fruit slicing game cut-face sprite, square raster PNG cutout.
Style/medium: photorealistic fresh fruit studio macro product photography, bright naturally saturated edible colors, realistic moist flesh and rind detail, soft upper-left studio light matching a fresh whole-fruit product photo. No cartoon or plastic rendering.
Scene/backdrop: genuinely transparent alpha background, isolated floating circular fruit cross-section, no ground plane and no cast shadow.
Composition/framing: ONE complete rounded flat fruit cross-section viewed directly face-on, full circular face with rind all around, centered and filling 85 percent of square canvas with a clear transparent margin on all sides. The cross-section is a full round disc, NOT a semicircle, NOT a wedge, NOT two pieces. It will be split into left and right pieces by a game renderer. Little visible side thickness, no perspective tilt.
Constraints: only the edible fruit disc, no hands, plate, knife, leaves, stems, extra pieces, typography, labels, logo, border, watermark, floor, or background. Keep all fruit texture naturally opaque and clearly recognizable.
```

- **watermelon**: a freshly cut full round watermelon cross-section, vivid juicy red flesh with natural scattered black teardrop seeds, a narrow creamy white rind ring, and a thin green striped peel edge. Full circular red face with the entire rind.
- **orange**: a freshly cut full round orange cross-section, luminous naturally orange juicy citrus segments arranged radially, delicate white membranes and small pale center, narrow white pith ring with orange peel edge. Full circular orange face.
- **kiwi**: a freshly cut full rounded kiwi cross-section, vivid natural green juicy flesh with fine radial lighter fibers, an ivory oval center, a distinct radial ring of tiny black seeds, and a very thin brown fuzzy skin edge. Full rounded kiwi face.
- **apple**: a freshly cut full rounded red apple cross-section through the core, creamy pale ivory flesh, a natural small five-lobed seed core with a few brown apple seeds, and a thin vivid red peel edge. Full rounded apple face, no stem or leaves.
- **pineapple**: a freshly cut full round pineapple cross-section, rich juicy golden yellow flesh with fine natural radial fibers, slightly paler small central core, a thin irregular golden-brown diamond-textured rind edge. Full circular pineapple face, no leaves or crown.
- **plum**: a freshly cut full rounded red-purple plum cross-section, juicy golden yellow-orange flesh subtly red near the thin deep red-purple skin edge, with one recognizable small textured brown plum pit in the center. Full rounded plum face, no stems or leaves.

