# From Java + XML to Jetpack Compose: I Rewrote the Spreadtrum Flashing Tool with the Official Liquid Glass Library

> Project: `com.ryu.sprdflasher`　Version `1.0.0-rz`
> Stack: Kotlin + Java mixed · Jetpack Compose 1.12.0 · Material3 1.4.0 · `io.github.kyant0:backdrop:2.0.1`
> This article only covers what I actually did, including the parts I made a mess of.

> **Note:** This is the English translation of the original Chinese article. [中文版](技术文章-液态玻璃重写.md)

---

## Table of Contents

- [Introduction: What Was Wrong with the Old Project](#introduction-what-was-wrong-with-the-old-project)
- [Chapter 1: How Liquid Glass Actually Works](#chapter-1-how-liquid-glass-actually-works)
- [Chapter 2: From the Official Catalog to an Actual Port](#chapter-2-from-the-official-catalog-to-an-actual-port)
- [Chapter 3: Capability Tiers and Degradation Strategy](#chapter-3-capability-tiers-and-degradation-strategy)
- [Chapter 4: Performance — Every Approach I Tried and Failed](#chapter-4-performance--every-approach-i-tried-and-failed)
- [Chapter 5: Decoupling the Flashing Engine from the UI](#chapter-5-decoupling-the-flashing-engine-from-the-ui)
- [Chapter 6: Build and Pitfalls](#chapter-6-build-and-pitfalls)
- [Epilogue: On Trade-offs](#epilogue-on-trade-offs)

---

## Introduction: What Was Wrong with the Old Project

This tool started out as an Android project built with Java + XML Views, compiled directly on a phone with AIDE. It worked, and it wasn't missing features — USB communication, FDL loading, PAC firmware parsing, partition table read/write, batch flashing, Kickto listening; the core engine `UsbFlashEngine.java` was 152KB.

But the problems at the UI layer were obvious:

**First, the styling was basically stitched together out of drawables.** Every card and every button was an XML shape; rounded corners, gradients, and strokes were all piled up out of `bg_xxx.xml` files. Changing one bit of styling meant editing a dozen drawables, and after editing you still had to set them back one by one in `MainActivity` with `setBackgroundResource()`. That `int[] btnIds = { btnConnect, btnReadPart, ... }` loop that calls `setBackgroundResource` in the old code came from exactly this.

**Second, the UI and business logic were completely entangled.** One `MainActivity.java` was 113KB, `findViewById` pulled in dozens of widgets, `runCmd()` had a huge `if/else` dispatch chain, and engine callbacks mutated TextViews directly inside the Activity.

**Third, complex animation wasn't possible.** Doing things like "a glow that spreads and follows your finger on press" or "velocity-driven stretching while dragging" with XML Views is extremely expensive — it's basically all hand-written custom Views.

So I decided to rewrite the UI. And the goal was clear: **make it feel like the liquid glass in iOS 26**, not just a simple translucent frosted blur.

There was one practical constraint here: `AndroidLiquidGlass` (Kyant0) has been a **Compose Multiplatform library** since 2.0.0. The official release notes put it bluntly:

> *"Backdrop becomes a Compose Multiplatform library now! Remove Android related effects, use Compose version instead"*

In other words, it **has no View-based API**. Dropping it into the old project with `implementation` and calling it directly is simply not possible. That nailed down the direction of the rewrite: I had to move to Compose.

---

## Chapter 1: How Liquid Glass Actually Works

This chapter is the core of the whole article. A lot of people think "liquid glass" is just "translucent white background + Gaussian blur + a rim of highlight" — that's the most primitive version. Real liquid glass has to do three things: **genuine background blur, genuine edge refraction, and chromatic dispersion at the edges**. Let me break them down.

### 1.1 The Backdrop Recording Mechanism

In Compose, drawing ultimately goes through `GraphicsLayer`. What this library does is: **record the content of a particular layer of UI into a GPU texture on its own**, and then have every glass component sample from that texture instead of from the screen buffer.

The code is only two lines:

```kotlin
// 1. Create a "backdrop layer" handle
val backdrop = rememberLayerBackdrop()

// 2. Attach the wallpaper layer; this layer's content gets recorded into a texture
Box(
    Modifier
        .fillMaxSize()
        .layerBackdrop(backdrop)
) {
    WallpaperBackground(blurRadius = 6f, dim = Color(0x140B1220))
}
```

```
┌──────────────────────────────────────┐
│  Box + Modifier.layerBackdrop(...)   │  ← recorded
│  ┌────────────────────────────────┐  │
│  │ Wallpaper JPG + RenderEffect blur│  │
│  │ + dim layer + vertical gradient  │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
              │
              │  recorded into a GPU texture
              ▼
      ╔═════════════════╗
      ║  Backdrop texture║  ← all glass samples from this afterwards
      ╚═════════════════╝
        │      │      │
        ▼      ▼      ▼
   glass card  glass button  bottom glass nav
```

The key point: **this layer can be any content**, not necessarily a static image. Put a video, a gradient, or a scrolling list in there, and the glass will refract the live content at that spot. This also explains why "the busier the background, the more obvious the glass effect" — if the background is pure white, whatever the glass refracts is white, and it just looks like a dirty plastic sheet. That's why I added a dim layer and vertical gradients specifically for the wallpaper: so the glass's refraction and highlights have something to "see".

### 1.2 AGSL Shader Refraction

What's actually doing the work is `RuntimeShader` (AGSL, an API only available on Android 13 / SDK 33+). It's a fragment shader: **every pixel on screen that belongs to the glass has to run through this program**.

The pseudocode looks roughly like this:

```glsl
// Fragment shader: takes the current pixel coordinate, outputs that pixel's color
uniform shader backdropTex;   // the backdrop texture recorded earlier
uniform float2 size;          // glass panel size
uniform float  cornerRadius;  // corner radius
uniform float  refractHeight; // refraction range (how wide the edge band where bending starts)
uniform float  refractAmount; // refraction strength (max offset in pixels)
uniform float2 lightDir;      // light source direction

// ---- ① SDF: signed distance field ----
// Returns negative = inside the glass, absolute value = distance to the edge
float roundedRectSDF(float2 p, float2 halfSize, float r) {
    float2 d = abs(p) - halfSize + r;
    return length(max(d, 0.0))
         + min(max(d.x, d.y), 0.0)
         - r;
}

// ---- ② Normal: the gradient of the SDF ----
float2 sdfGradient(float2 p) {
    float e = 0.5;
    return normalize(float2(
        roundedRectSDF(p + float2(e,0), ...) - roundedRectSDF(p - float2(e,0), ...),
        roundedRectSDF(p + float2(0,e), ...) - roundedRectSDF(p - float2(0,e), ...)
    ));
}

half4 main(float2 p) {
    float d = roundedRectSDF(p, size * 0.5, cornerRadius);

    // Outside the glass, discard immediately
    if (d > 0.0) discard;

    // ---- ③ Edge weight: the closer to the edge, the stronger the refraction ----
    float edge = 1.0 - smoothstep(0.0, refractHeight, -d);

    // ---- ④ Derive the normal ----
    float2 n = sdfGradient(p);

    // ---- ⑤ Refraction sampling (offset along the normal) ----
    float2 offset = n * refractAmount * edge;
    half4 refracted = backdropTex.eval(p + offset);

    // ---- ⑥ Dispersion: R/G/B use different refractive indices ----
    half r = backdropTex.eval(p + offset * 1.03).r;
    half g = refracted.g;
    half b = backdropTex.eval(p + offset * 0.97).b;
    half4 col = half4(r, g, b, 1.0);

    // ---- ⑦ Fresnel highlight: angle between the normal and the light source ----
    float fresnel = pow(1.0 - abs(dot(n, lightDir)), 3.0);
    col.rgb += fresnel * edge * 0.35;

    return col;
}
```

Breaking it down, here's what each step solves:

**The SDF (signed distance field)** is the biggest trick in this technique. The traditional approach has to work out "is this pixel inside the rounded rect, and how far from the edge is it", which needs several `if` checks. The SDF turns "distance" into a continuous function: negative inside, positive outside, zero exactly on the boundary. With a continuous distance, all the later interpolation for "stronger effect at the edge" needs only a single `smoothstep` — no branches, and the GPU doesn't stall on branch divergence.

**Deriving the normal** is where the "lens feel" comes from. Real glass has thickness; at the edges the curvature is high so the normal is tilted, while in the center the normal is perpendicular. The gradient of the SDF happens to be the normal direction (because the rate of change of the SDF along the boundary is 0, and it's maximal along the normal). Taking the gradient as the normal is equivalent to automatically computing a "convex lens" geometry for the glass panel.

**Refraction sampling** then offsets the texture coordinates using that normal. The offset is 0 in the center (normal perpendicular, you see what's directly behind), and maximal at the edges (normal tilted, light gets "bent"). So the glass is clear in the middle and wavy at the edges — that's the essence of liquid glass's "sense of thickness".

**Dispersion** is that final rainbow rim. In real optics different wavelengths have different refractive indices, with red bending least and violet most. Here we sample the R/G/B channels with three different coefficients and recombine them, and colored fringes appear naturally at the edges. This effect is very sensitive to its parameters — **too strong and text goes fuzzy**; the log view of a flashing tool is full of hex addresses, so I tuned it on the conservative side.

**The highlight** is the dot product of the normal and the light direction, with a `pow` to control falloff, simulating Fresnel reflection — brighter where the grazing angle is large.

In Compose you actually don't need to write any of this yourself; the library's `lens()` does exactly this:

```kotlin
effects = {
    blur(blurRadius.toPx())
    lens(
        refractionHeight = 16.dp.toPx(),
        refractionAmount = 18.dp.toPx(),
        depthEffect = false,          // whether to simulate panel thickness
        chromaticAberration = true    // turn dispersion on/off
    )
}
```

`refractionHeight` controls "how wide the edge band is where bending begins", and `refractionAmount` controls "the strength of the offset". `GlassSurface.kt` exposes both as constructor parameters, giving different values to different tiers of glass:

```kotlin
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 16.dp,
    refractionAmount: Dp = 18.dp,
    chromaticAberration: Boolean = true,
    highlightAngle: Float = 45f,
    highlightAlpha: Float = 1f,
    ...
) {
    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    chromaticAberration = chromaticAberration
                )
            },
            highlight = { Highlight(style = HighlightStyle.Default(angle = highlightAngle)) },
            shadow = { Shadow.Default },
            innerShadow = if (showInnerShadow) { { InnerShadow.Default } } else null
        )
    )
}
```

The tuning rules can be summarized in this table:

| What you want | Which parameter to tweak |
|---|---|
| Glass looks "thicker", stronger lens feel | Increase `refractionAmount` |
| Wider edge bending band (the whole panel twists) | Increase `refractionHeight` |
| More obvious rainbow edges | Enable `chromaticAberration` / raise the dispersion coefficient |
| More frosted, more "milky" | Increase `blurRadius` |
| More like "paper stuck onto glass" | Add an `onDrawSurface` to paint a translucent color layer |

### 1.3 Capability Tiers and Degradation

`RuntimeShader` only exists as of API 33, while minSdk is 23, so tiers are mandatory:

```
┌──────────────────┬──────────────────────────────────────────┐
│ Android 13+      │ RuntimeShader (AGSL) real refraction + dispersion │
│ SDK 33 and up    │ Full liquid glass                        │
├──────────────────┼──────────────────────────────────────────┤
│ Android 12       │ Only RenderEffect.createBlurEffect       │
│ SDK 31 ~ 32      │ Real blur, but no refraction/dispersion  │
├──────────────────┼──────────────────────────────────────────┤
│ Android 6 ~ 11   │ Translucency + gradient + stroke + highlight │
│ SDK 23 ~ 30      │ Frosted-glass look (similar, no optics)  │
└──────────────────┴──────────────────────────────────────────┘
```

These three tiers aren't something I invented; they're the objective boundaries of the hardware and system APIs. What needs to be clear is: **the old versions don't "crash", they just don't look as good**. Every call to a newer API sits inside a `Build.VERSION.SDK_INT` check, so those classes are never even loaded on old systems, and `NoClassDefFoundError` can't happen.

The approach is to let the library probe for itself: if the capability is there, it goes through AGSL; if not, it takes the fallback path. Business code doesn't need to care at all — the `GlassSurface(...)` call site is identical across all three tiers; only what ends up being drawn changes.

### 1.4 The Self-Sampling Problem

This was the first theoretical pit I fell into, and it deserves its own section.

`layerBackdrop(backdrop)` records **everything inside that Box layer** into the texture. If you put the glass components inside that same Box, you have a problem:

```
The glass needs to sample the backdrop,
but the backdrop layer is currently recording the glass,
and recording the glass requires sampling the backdrop……
```

The result is recursion — in the mild case a garbled image, in the bad case a straight-up crash. This problem exists in any pipeline of the form "capture background → render foreground → merge foreground into background".

The official library handles it elegantly: **the background layer is its own Box, and the glass layer is outside the recorded range**.

```kotlin
Box(Modifier.fillMaxSize()) {

    // Only this layer is recorded, and it contains only the wallpaper
    Box(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
    ) {
        WallpaperBackground(blurRadius = 6f, dim = Color(0x140B1220))
    }

    // The glass layer is outside the recorded range, so sampling the backdrop can't sample itself
    Column(Modifier.fillMaxSize()) {
        GlassSurface(backdrop = backdrop, ...) { /* top bar */ }
        GlassSurface(backdrop = backdrop, ...) { /* progress bar */ }
        // ……
    }
}
```

This structure looks like the obvious thing to do, but I got it wrong at first — I put the glass and the wallpaper inside the same recorded Box and then wondered why the image kept nesting inside itself.

---

## Chapter 2: From the Official Catalog to an Actual Port

The official Catalog is a complete sample app; the job was to extract the components and stuff them into my own project.

### 2.1 Porting Checklist

The files ported from the official Catalog live in `ui/compose/catalog/`:

```
ui/compose/catalog/
├── LiquidBottomTab.kt        1.8KB   a single tab
├── LiquidBottomTabs.kt      12.4KB   bottom navigation (the most complex component)
├── LiquidButton.kt           4.7KB   capsule button
├── LiquidSlider.kt           8.7KB   progress slider
├── DampedDragAnimation.kt    6.4KB   damped drag animation
└── InteractiveHighlight.kt   5.1KB   interactive highlight
```

There was only one porting principle: **don't change a single parameter**. For example, the official effect chain for `LiquidButton` is `vibrancy() + blur(2dp) + lens(12dp, 24dp)`, height 48dp, capsule shape — copied verbatim, not one character touched. That's the only way it can look exactly like the official Catalog.

Only two kinds of things were changed:

**First, package names and imports, pointing at `com.kyant.shapes.Capsule`.** The official `Capsule()` shape comes from `io.github.kyant0:shapes:1.2.1`, so that dependency has to be added separately — it's already in the official POM, but declaring it explicitly is safer.

**Second, trimming down the exposed parameters.** Official components tend to be "you can pass anything"; the ported versions keep only the openings the project actually uses.

### 2.2 The Bottom Bar's "Four-Layer" Structure

Bottom navigation is the most complex component in the whole project; officially it uses 4 layers stacked on top of each other. The first time I read the source I thought "isn't this a bit much", until I tried simplifying it and found that every layer does something.

```
┌───────────────────────────────────────────────┐
│ ① Base glass bottom bar                        │
│    drawBackdrop: vibrancy + blur(8dp)          │
│                  + lens(24dp, 24dp)            │
│    height 64dp, Capsule                        │
├───────────────────────────────────────────────┤
│ ② Upper half: blue pressed tint layer (ghost with alpha = 0) │
│    layerBackdrop(tabsBackdrop) → recorded as a texture │
│    colorFilter = tint(accentColor)             │
│    lens strength = 24dp * pressProgress        │
├───────────────────────────────────────────────┤
│ ③ Thumb (the one actually moving)              │
│    backdrop = rememberCombinedBackdrop(        │
│                   backdrop, tabsBackdrop)       │
│    lens(10dp*p, 14dp*p, chromaticAberration)   │
│    + Highlight + Shadow + InnerShadow          │
├───────────────────────────────────────────────┤
│ ④ Text (content)                               │
└───────────────────────────────────────────────┘
```

The key is the interplay between layers ② and ③:

**Layer ② is an `alpha(0f)` "ghost layer"** — it's invisible, but as long as it carries `layerBackdrop(tabsBackdrop)`, its content (that is, the row of text tinted blue by `tint(accentColor)`) gets recorded into its own separate texture.

**The thumb in layer ③ samples two sources at once**: `rememberCombinedBackdrop(backdrop, tabsBackdrop)`. In other words, the thumb can see both the wallpaper behind it and the "blue text" texture.

The result: when the thumb sweeps over a tab, it **refracts** that tab's blue text into its own body, and because dispersion is on, the edges get a rainbow glow. That's what a "liquid glass thumb" should look like — it isn't sliding a translucent slab underneath the text, it's **genuinely treating that region as an optical medium**.

In the official source it looks roughly like this:

```kotlin
// ② ghost layer
Row(
    Modifier
        .clearAndSetSemantics { }
        .alpha(0f)
        .layerBackdrop(tabsBackdrop)              // recorded as a separate texture
        .drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                val p = dampedDragAnimation.pressProgress
                vibrancy()
                blur(8.dp.toPx())
                lens(24.dp.toPx() * p, 24.dp.toPx() * p)
            },
            onDrawSurface = { drawRect(containerColor) }
        )
        .height(56.dp)
        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor))
)

// ③ thumb
Box(
    Modifier
        .drawBackdrop(
            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
            shape = { Capsule() },
            effects = {
                val p = dampedDragAnimation.pressProgress
                lens(
                    10.dp.toPx() * p,
                    14.dp.toPx() * p,
                    chromaticAberration = true
                )
            },
            highlight = { Highlight.Default.copy(alpha = p) },
            shadow = { Shadow(alpha = p) },
            innerShadow = { InnerShadow(radius = 8.dp * p, alpha = p) },
            layerBlock = {
                // Velocity stretch: when the thumb gets "flung", it stretches horizontally and squashes vertically
                val velocity = dampedDragAnimation.velocity / 10f
                scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
            }
        )
        .height(56.dp)
        .fillMaxWidth(1f / tabsCount)
)
```

Note the velocity stretch inside `layerBlock`: `scaleX` shrinks its denominator inversely with velocity, so when the thumb is flung it gets longer horizontally and flatter vertically. It's a very subtle bit of feel, but once you add it the whole drag "comes alive".

### 2.3 Splitting the Official DampedDragAnimation into 5 Animatables

The reason the official feel is so good only became clear after reading `DampedDragAnimation.kt`.

My original hand-written drag used an under-damped spring with `dampingRatio = 0.32`, i.e. one that **bounces back and forth a few times**. It looked "lively", but bouncing three times on every tab switch gets annoying fast.

What the official version uses is:

```kotlin
private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)
```

`dampingRatio = 1.0` means **over-damped** — no rebound at all, just smooth tracking. It stops when it reaches the target. Clean.

More importantly, it splits the state into 5 independent `Animatable`s:

```
Animatable: value          ← position, over-damped
Animatable: velocity       ← velocity, under-damped (this is where the "fling" feel comes from)
Animatable: pressProgress  ← press progress, over-damped
Animatable: scaleX         ← horizontal scale
Animatable: scaleY         ← vertical scale
```

Why split them? Because **a finger is multi-dimensional**. Pressing, dragging, and releasing overlap in time: while pressing, pressProgress is rising; while dragging, value is tracking; at the moment of release, velocity still has momentum and scale is still springing. If you express all of this with one animation curve, they necessarily fight each other — dragging interrupts the press animation, releasing interrupts the drag animation.

Once split, each dimension has its own spring and its own clock, with no interference. That's the principle behind "multiple gestures don't fight".

This was ported verbatim into `catalog/DampedDragAnimation.kt`, with the comparison recorded in the comments:

```kotlin
/**
 * Why the official swipe feels "stable":
 * dampingRatio = 1.0 means over-damped, no rebound at all, just smooth tracking.
 * My earlier version used 0.32, which is under-damped and bounces back and forth,
 * so the feel was completely different.
 * It also splits position/velocity/press progress/scale into 5 independent
 * Animatables, each with its own spring parameters, non-interfering — that is
 * the real reason multiple gestures don't fight.
 */
```

The thumb settling after a tab switch also uses over-damping:

```kotlin
onDragStopped = {
    val targetIndex = targetValue.toInt().coerceIn(0, tabsCount - 1)
    currentIndex = targetIndex
    animateToValue(targetIndex.toFloat())
    animationScope.launch {
        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
    }
}
```

### 2.4 GlassButton: Official API Plus Local Roles

The official `LiquidButton` only accepts two color parameters, `tint` and `surfaceColor`. The project needs to distinguish five semantic roles — "primary, success, danger, warning, secondary" — and there were already dozens of `GlassButton(backdrop, "text", { ... })` call sites in the pages.

So I wrapped the official component in a `GlassButton`, **keeping the call signature unchanged** and mapping internally onto the official parameters:

```kotlin
enum class GlassRole(val tint: Color, val textColor: Color) {
    Primary(Color(0xFF4F7CFF), Color.White),
    Success(Color(0xFF22C55E), Color.White),
    Danger(Color(0xFFEF4444), Color.White),
    Warning(Color(0xFFD97706), Color.White),
    Secondary(Color(0x30FFFFFF), Color(0xFF0B1220))
}
```

The coloring uses the same trick as the official code: first tint with `BlendMode.Hue`, then overlay a 75%-transparent layer of the same color. Why two passes? Because `Hue` blend mode only changes the hue while keeping the underlying lightness — if you only tint once, the color gets pulled around by the background's brightness; overlaying a translucent solid color pulls the lightness back too. The official `LiquidButton` uses exactly this combination, so I just carried it over.

### 2.5 Refresh Rate Adaptation

While porting I also found a problem the official code doesn't handle: **the animation parameters were tuned for one particular refresh rate, so the feel changes on other devices.**

60Hz is 16.7ms per frame, 120Hz is 8.3ms, 144Hz is 6.9ms. The same 90ms press tween renders half as many frames on a high-refresh display, so visually it reads as "a flash" rather than "a press".

So I wrote `DeviceRefreshRate.kt`, which groups by refresh rate and hands out different durations and springs:

```kotlin
fun scaleDuration(base60Ms: Int): Int {
    return when {
        hz >= 144 -> (base60Ms * 0.78f).roundToInt()
        hz >= 120 -> (base60Ms * 0.84f).roundToInt()
        hz >= 90  -> (base60Ms * 0.92f).roundToInt()
        else      -> base60Ms
    }
}

fun pressSpring(): SpringSpec<Float> {
    return when {
        hz >= 144 -> SpringSpec(dampingRatio = 0.52f, stiffness = 420f)
        hz >= 120 -> SpringSpec(dampingRatio = 0.48f, stiffness = 470f)
        hz >= 90  -> SpringSpec(dampingRatio = 0.42f, stiffness = 540f)
        else      -> SpringSpec(dampingRatio = 0.32f, stiffness = 620f)
    }
}
```

On high-refresh screens the stiffness goes down and damping goes up, so it feels the same as 60Hz. Detecting the refresh rate has its own trap: `Display.mode.refreshRate` may return non-integer values like `59.94` / `119.88` (a legacy of PAL systems), so it has to be normalized:

```kotlin
fun normalizeHz(raw: Float): Int {
    return when {
        raw >= 138f -> 144
        raw >= 115f -> 120
        raw >= 85f  -> 90
        raw >= 55f  -> 60
        else        -> 60
    }
}
```

---

## Chapter 3: Capability Tiers and Degradation Strategy

The previous chapter established that AGSL only exists on SDK 33+. This chapter is about how I actually dealt with that, and why in the end I didn't build the idealized "automatic degradation + automatic recovery".

First, the summary table:

| Android version | SDK | Glass appearance | What the user perceives |
|---|---|---|---|
| 13 and up | 33+ | Real-time refraction + dispersion + dynamic highlight | Full liquid glass |
| 12 | 31–32 | `RenderEffect` real blur + translucency + highlight | Like frosted glass, no edge bending |
| 6 ~ 11 | 23–30 | Translucency + gradient + stroke | Like frosted acrylic, you can see the layering |

There's a counterintuitive thing here: **degrading doesn't make the UI progressively "murkier" as you go older — it makes it progressively "flatter".**

The Android 12 tier still has real blur, so the background is blurred, only the edges don't bend. Android 11 and below doesn't even have blur, so it can only simulate "a pane of glass" with a translucent base color + a stroke; the background is still sharp, you're just looking at it through a colored film.

This is also why I insisted on putting the wallpaper blur in the background layer rather than the glass layer:

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen  // RenderEffect needs an offscreen layer first
            renderEffect = if (blurRadius > 0f) {
                android.graphics.RenderEffect
                    .createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            } else null
        }
)
```

If the wallpaper itself is blurred, then whatever every glass layer "sees through" is naturally blurred too, so even on old systems without AGSL it still looks glassy — rather than "a high-resolution photo crushed under a sheet of transparent plastic".

The line `compositingStrategy = CompositingStrategy.Offscreen` is easy to overlook too: `RenderEffect` must be applied to an offscreen layer; if you don't go offscreen first, the blur applies to the whole window and blurs the text on top too.

As for the degradation logic itself, I didn't write a pile of `if (Build.VERSION.SDK_INT >= 33)` checks with two hand-written code paths. The library probes the capability itself: if AGSL is available it uses the shader, otherwise it takes the alternative path. The business layer only needs to know one thing: **the same `GlassSurface` call works on every version, it just doesn't look the same**.

That's one of the things the library does well — but it's also the root of all the performance problems in the next chapter. It looks good because it's genuinely computing in real time.

---

## Chapter 4: Performance — Every Approach I Tried and Failed

This is the chapter I most wanted to write, because it's the most honest one.

### 4.1 What the Problem Is

The AGSL shader in liquid glass is **computed in real time**. Each glass layer = one fragment shader invocation covering every pixel of that glass panel.

The tools page has 7 glass cards, each card also contains `GlassButton`s (densely packed, 20+ of them), plus bottom navigation and the top bar. While scrolling, every frame has to run **20+ shader instances**, each traversing its own pixel area.

The result: scrolling drops frames noticeably.

Android Studio's Layout Inspector is no help (this is GPU-side cost, not a layout problem), and the Profiler only tells you "the GPU is busy". What is certain: the frame drops track the number of glass surfaces — the more glass a page has, the more it stutters, and the tools page stutters worst.

### 4.2 Every Approach I Tried

**❌ Approach 1: reduce the number of cards**

The most direct idea: 7 cards is too many, merge them into 4.

I did it, finished it, and it did look good. But the feedback was crystal clear: **bring back the original layout**. Merging scrambled the functional grouping, made the operation paths longer, and turned a page where you could "see all the buttons at a glance" into one where you "have to open something to see them".

**Rolled back.** I'm noting this one down because it's the most easily overlooked: the precondition of performance optimization is not changing the requirements. The user didn't ask you to merge cards; if you merge them, you've changed the requirements.

**❌ Approach 2: downsample and pre-bake the wallpaper**

Second idea: the glass blur is essentially "a blur of the base color", so if I shrink the wallpaper to something tiny in advance and then scale it back up, wouldn't I get the blur? And it only costs once, with no real-time cost.

Sounds perfect. What it actually looked like: **the wallpaper turned into blocky mosaic color patches**.

The reason is simple: shrinking to 96px and scaling back up is just reconstructing a low-resolution image with bilinear interpolation. A real `RenderEffect` Gaussian blur is a **per-pixel weighted convolution** that preserves the continuity of color transitions; "shrink-then-upscale" **throws information away** and then guesses it back. The former is blur; the latter is pixelation.

This approach failed completely. And it failed obviously — you can tell it's wrong at a glance; it's not "slightly worse".

**❌ Approach 3: turn off refraction while scrolling**

Third idea: turn off `lens()` while scrolling, keep only `blur()`, and restore it when scrolling stops.

What it produced: **flicker**.

Because the scroll state is continuous, `isScrollInProgress` flips within a single frame. Refraction on → off → on visually means the edges "jump". And the abrupt change in `lens()` parameters doesn't go through an animation — it's a hard cut.

This approach is flawed in principle: **dynamically swapping materials is, by definition, letting the material change**.

**❌ Approach 4: add `tools:overrideLibrary` to force minSdk 21** (not really a performance approach, but I hit it around the same time)

I wanted to support Android 5.0 (SDK 21), because some old Spreadtrum devices are still stuck on 5.0. Adding `tools:overrideLibrary` didn't help — the `minSdk` of `compose.animation.core`, `compose.animation`, `foundation-layout`, and `ui-unit` is hardcoded to 23, and adding the override repeatedly still wouldn't pass compilation.

In the end minSdk had to be 23.

### 4.3 What I Do Now

Between "accept the dropped frames" and "change the requirements", I found a compromise with the **smallest change that doesn't damage the visuals**:

**First, switch the tools page to `LazyColumn`.**

It used to be `Column + verticalScroll`, where all cards were in the composition tree, and even when scrolled off-screen Compose would still lay them out and draw them (just clipped). `LazyColumn` takes off-screen items out of the composition tree entirely, which in theory saves the off-screen shader cost.

```kotlin
val scroll = rememberLazyListState()

LazyColumn(
    state = scroll,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
) {
    item { GlassCard(backdrop) { /* FDL selection */ } }
    item { GlassCard(backdrop) { /* PAC firmware */ } }
    // ……
}
```

**Second, during scrolling only degrade the buttons' `lens`, never the cards'.**

I added a global switch:

```kotlin
object CheapGlass {
    // Use a Compose snapshot state rather than a plain field:
    // at some instant during scrolling no button happens to recompose, so a change
    // to a plain field would be missed, leaving cheap mode stuck on or off
    // after scrolling ends.
    private var _enabled by mutableStateOf(false)
    var enabled: Boolean
        get() = _enabled
        set(value) { _enabled = value }
}
```

Then the tools page observes the scroll state:

```kotlin
LaunchedEffect(scroll) {
    snapshotFlow { scroll.isScrollInProgress }
        .distinctUntilChanged()
        .collectLatest { scrolling ->
            if (scrolling) {
                CheapGlass.enabled = true
            } else {
                delay(180)               // after stopping, wait another 180ms to avoid jitter when fling ends
                CheapGlass.enabled = false
            }
        }
}
```

The button decides internally whether to run `lens`:

```kotlin
val pressed by interaction.collectIsPressedAsState()
// In cheap mode and not pressed, drop lens and layerBlock: a static button doesn't need per-frame refraction
val cheap = CheapGlass.enabled && !pressed

effects = {
    vibrancy()
    blur(2.dp.toPx())
    if (!cheap) {
        lens(12.dp.toPx(), 24.dp.toPx())
    }
}
```

Why doesn't this flicker the way Approach 3 did? Because **what's being degraded is the buttons, not the cards**.

For the entire duration of scrolling, the cards (the largest glass surfaces on screen) keep full refraction; only the buttons change — and the button's `lens(12dp, 24dp)` over such a small area contributes almost nothing visually. While scrolling, the user's eyes are following the cards, and nobody is staring at whether the button edges bend.

Also, `cheap` includes `&& !pressed`: the instant a finger presses, `cheap` turns false, `lens` comes straight back, and the press feedback is fully preserved.

**Third, accept reality.**

Honestly, the first two are only "mitigation", not "a solution". The tools page still drops frames while scrolling on the worst machines, and that's a fact.

### 4.4 The Core Conclusion

There's really only one thing I want to say in this chapter:

> **This library's visual quality and its performance cost are hard-bound together.**

If you want the iOS-style liquid glass look, you necessarily pay the shader cost. It's not that it's "badly implemented" — it's the physical cost of this path: real refraction = per-pixel sampling offset by the normal = every pixel has to be computed. You can't get the same result with a "cheaper algorithm" — Approach 2 (downsampling) and Approach 3 (refraction off) already proved that a cheaper result is a different result.

So at the crossroads there are only two paths:

```
        ┌─────────────────────────┐
        │  Do you want the liquid glass look? │
        └───────────┬─────────────┘
                    │
        ┌───────────┴────────────┐
        ▼                        ▼
   ✅ Yes                    ❌ No
        │                        │
   Accept shader cost       Go back to translucent frosted
        │                        │
   Frame drops on low-end   Smooth on all devices
```

I chose the left one. The reason is that the whole point of starting this project was "I want that effect" — if I cut it down to frosted for the sake of smoothness, the rewrite has no meaning. The old project already had translucent drawables.

But I also left an escape hatch in the code: `CheapGlass` is a global switch, and machines that really can't take it can degrade all buttons through it. The very existence of that switch is an honest record of the conclusion above.

---

## Chapter 5: Decoupling the Flashing Engine from the UI

The UI is done; let me talk about the other thread: how this app actually flashes a device.

### 5.1 The BSDL Protocol, Briefly

Spreadtrum flashing uses **BSDL** (Spreadtrum Bootloader Download Protocol). The frame format is HDLC-style:

```
┌──────┬────────┬────────┬──────────┬────────┐
│ 0x7E │ TYPE   │ LEN    │ PAYLOAD  │ CRC16  │
│ hdr  │ 1 byte │ 2 byte │ N bytes  │ check  │
└──────┴────────┴────────┴──────────┴────────┘
                 0x7D is the escape character; any 0x7E/0x7D in the data must be escaped
```

The command set defined in `BslProtocol.java` (excerpt):

```java
public static final int CMD_CONNECT       = 0x00;
public static final int CMD_START_DATA    = 0x01;
public static final int CMD_MIDST_DATA    = 0x02;
public static final int CMD_END_DATA      = 0x03;
public static final int CMD_EXEC_DATA     = 0x04;
public static final int CMD_NORMAL_RESET  = 0x05;
public static final int CMD_READ_FLASH    = 0x06;
public static final int CMD_ERASE_FLASH   = 0x0A;
public static final int CMD_REPARTITION   = 0x0B;
public static final int CMD_READ_PARTITION= 0x2D;
public static final int CMD_CHECK_BAUD    = 0x7E;
public static final int CMD_POWER_OFF     = 0x17;
```

### 5.2 The FDL Two-Stage Bootstrap

Spreadtrum flashing must go through a two-stage flash download loader, **FDL**:

```
BootROM stage (code baked into the chip's ROM)
   │
   ├─ USB controlTransfer(0x21, 34, 0x601)     ← establish BROM link
   ├─ CMD_CHECK_BAUD(1) → expect REP_VER
   ├─ parse VER, determine whether it's SPRD3 (not SPRD3 = secureBoot)
   ├─ CMD_CONNECT
   │
   ├─ send FDL1 (~37KB) @ addr1
   │    START_DATA → MIDST_DATA × N → END_DATA
   ├─ CMD_EXEC_DATA → ACK
   │
FDL1 stage
   ├─ CMD_CHECK_BAUD(4) × up to 12 retries → REP_VER
   ├─ extract the CHIP ID from the VER string ("CHIP ID = 0x...")
   ├─ CMD_CONNECT
   │
   ├─ send FDL2 (~435KB) @ addr2
   │    (note: the FDL2 stage has no CRC16, it uses a sum checksum)
   ├─ CMD_EXEC_DATA → ACK or INCOMPATIBLE_PARTITION(0x96)
   │
FDL2 stage
   ├─ CMD_READ_PARTITION      read the partition table
   ├─ CMD_START/MIDST/END     write a partition
   ├─ CMD_READ_FLASH          read a partition
   ├─ CMD_REPARTITION         repartition
   └─ CMD_NORMAL_RESET / POWER_OFF
```

The heart of chunked sending is just one loop:

```java
private void sendBuf(long saddr, boolean ed, int step, byte[] mem, int size) {
    resetSpeedCounter();
    byte[] data = new byte[8];
    w32be(data, 0, saddr);      // start address
    w32be(data, 4, size);       // total length
    encodeMsg(BslProtocol.CMD_START_DATA, data, 8);
    if (!sendAndCheck(5000)) throw new RuntimeException("START_DATA失败");

    for (int i = 0; i < size; ) {
        if (cancelled) throw new RuntimeException("已取消");
        int n = Math.min(size - i, step);
        encodeMsg(BslProtocol.CMD_MIDST_DATA, mem, i, n);
        if (!sendAndCheck(5000)) throw new RuntimeException("MIDST_DATA失败");
        i += n;
        updateSpeed(n);
        progress((int) ((i * 100L) / size), "发送中...");
    }

    if (!ed) return;
    encodeMsg(BslProtocol.CMD_END_DATA, null, 0);
    if (!sendAndCheck(5000)) throw new RuntimeException("END_DATA失败");
}
```

`step` defaults to 528 and is clamped between 64 and 0xF800. 528 isn't random — it aligns with the USB transfer boundaries and relates to the checksum block size.

On the flow itself, I have to thank the open-source project **`ilyakurdyukov/spreadtrum_flash` (that is, `spd_dump`)** — the FDL flow was checked against its implementation. The single most important lesson from it:

> **You only need to send the address; no execution address jump is required.**

Many documents present "send_file" and "exec" as two mandatory steps, and some implementations additionally send a "jump to exec_addr" packet. In reality, once Spreadtrum's FDL has `EXEC_DATA`, the chip simply starts running from the load address — no separate jump is needed. I wrote it the "needs a jump" way at first and was stuck for a long time; only after comparing against `spd_dump` did I realize that step was superfluous.

### 5.3 Built-in FDLs and the Chip Table

The project bundles FDLs for 7 chips, configured in `assets/fdl/chips.json`:

```json
[
  {
    "id": "sc9820e",
    "name": "SC9820E",
    "addr1": "0x5000",
    "addr2": "0x9EFFFE00",
    "fdl1": "fdl/sc9820e/fdl1.bin",
    "fdl2": "fdl/sc9820e/fdl2.bin",
    "size1": 37620,
    "size2": 435524
  },
  {
    "id": "ums9230",
    "name": "UMS9230",
    "addr1": "0x65000800",
    "addr2": "0xB4FFFE00",
    "size1": 60200,
    "size2": 1062104
  }
]
```

Note that **the UMS9230 addresses are completely different** (`0x65000800` / `0xB4FFFE00`, while most other chips use `0x5000` / `0x9EFFFE00`, and SC9863A/UMS512 use `0x5500`). This isn't a config mistake; it's a difference in platform architecture. If the address is wrong the FDL simply won't run, so chip selection has to be confirmed by the user — it can't be guessed automatically.

Users can also bypass the built-in FDLs entirely: pick FDL1 / FDL2 on the Tools (工具) page and fill in the addresses themselves. Once selected, "bind to the Connect (连接) page", and the Connect page's "send with one tap" will use that custom set.

### 5.4 Bridge Mode: Decoupling the UI from the Engine

This was the most important architectural decision of the rewrite.

The old project was a 113KB `MainActivity` that mutated `TextView`s directly from engine callbacks. After rewriting to Compose, stuffing engine callbacks straight into a Composable creates three problems:

1. Engine callbacks come from the engine thread, but mutating Compose state must happen on the main thread;
2. Composables would recreate the callbacks on recomposition, causing duplicate registration;
3. The UI layer would hold objects from the engine thread and could hit concurrency problems at any moment.

So I put a **`FlashEngineBridge`** (45KB) in between. Its responsibility is very clear:

```
┌──────────────────────────────────────────────┐
│  LiquidGlassScreen.kt / GlassButton / …      │
│  (pure Compose, read-only state)             │
└──────────────────────┬───────────────────────┘
                       │  vm.progressPct
                       │  vm.logs / vm.connected / vm.partitions
                       ▼
┌──────────────────────────────────────────────┐
│  FlashEngineBridge.kt                        │
│  ┌────────────────────────────────────────┐ │
│  │ var connected by mutableStateOf(false) │ │
│  │ var progressPct by mutableStateOf(0)   │ │
│  │ val logs = mutableStateListOf<String>()│ │
│  │ val partitions = mutableStateListOf<>()│ │
│  └────────────────────────────────────────┘ │
│                                              │
│  private val main = Handler(Looper.getMainLooper())│
│                                              │
│  → every engine callback goes through main.post { ... } before mutating state │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│  UsbFlashEngine.java (152KB, not a line changed) │
│  BslProtocol.java / UsbPermissionManager.java│
└──────────────────────────────────────────────┘
```

The heart of it is **thread switching**, those four `main.post` calls:

```kotlin
private val callback = object : UsbFlashEngine.Callback {
    override fun onLog(msg: String?) {
        msg?.let { appendLog(it) }
    }

    override fun onProgress(pct: Int, op: String?) {
        main.post {
            progressPct = pct.coerceIn(0, 100)
            progressOp = op ?: ""
        }
    }

    override fun onConnected(ok: Boolean, info: String?) {
        main.post {
            connected = ok
            appendLog(if (ok) "设备已连接: ${info ?: ""}" else "连接失败: ${info ?: ""}")
        }
    }
}
```

**I set myself one iron rule: don't change a single line of engine logic.**

`UsbFlashEngine`, `BslProtocol`, `UsbPermissionManager`, `LicenseDecoder/Generator/Manager`, and `NativeBinHelper` were all moved over exactly as they were. The Bridge's only job is "translating the engine's Java calls into state Compose can read", and translating the UI's taps back into engine calls.

The payoff is very practical: **the engine is the most expensive and most dangerous part of this tool** (a bad flash is a hardware loss). If I "optimized" the engine while rewriting the UI, then any problem that appeared would be impossible to attribute to either the UI or the engine.

The Bridge also does one more thing: it copies the old project's `runCmd` / `DsdlActivityImpl` command methods verbatim, down to the requestCodes:

```kotlin
companion object {
    const val REQ_STORAGE            = 100
    const val REQ_SAVE_LOG           = 200
    const val REQ_SAVE_LOG_AS        = 205
    const val REQ_SELECT_FILE        = 201
    const val REQ_SELECT_FDL         = 202
    const val REQ_SELECT_FDL2        = 208
    const val REQ_SELECT_PAC         = 203
    const val REQ_SELECT_APK         = 204
    const val REQ_SELECT_SAVE_DIR    = 209
    const val REQ_SELECT_BATCH_DIR   = 207
}
```

On the `MainActivity` side it's just a `when (requestCode)` forward:

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resultCode != RESULT_OK) return
    val uri: Uri = data?.data ?: return
    when (requestCode) {
        FlashEngineBridge.REQ_SELECT_FILE      -> flashVm.onWriteFilePicked(uri)
        FlashEngineBridge.REQ_SELECT_FDL       -> flashVm.onFdlPicked(uri)
        FlashEngineBridge.REQ_SELECT_FDL2      -> flashVm.onFdl2Picked(uri)
        FlashEngineBridge.REQ_SELECT_PAC       -> flashVm.onPacPicked(uri)
        FlashEngineBridge.REQ_SELECT_SAVE_DIR  -> flashVm.onSaveDirPicked(uri)
        FlashEngineBridge.REQ_SAVE_LOG_AS      -> flashVm.onSaveLogPicked(uri)
        FlashEngineBridge.REQ_SELECT_BATCH_DIR -> flashVm.onBatchDirPicked(uri)
    }
}
```

Even the SAF launcher is injected:

```kotlin
// In MainActivity:
flashVm.safLauncher = { requestCode, intent ->
    startActivityForResult(intent, requestCode)
}
```

That way the Bridge can launch file pickers without holding an Activity reference, so it can't leak.

### 5.5 A Real Initialization-Order Pitfall

There's a comment in the Bridge that I only wrote down after debugging for a whole afternoon:

```kotlin
// Old project's selectedChipAddr1/2 — must be declared before init{},
// because init → loadChips → selectChip / useBuiltinFdl (background thread) writes these
// delegated properties; if declared after init, the background thread may run and write
// them before initialization finishes, triggering an NPE.
var selectedChipAddr1 by mutableStateOf("0x5000")
    private set
var selectedChipAddr2 by mutableStateOf("0x9EFFFE00")
    private set
```

In Kotlin, `by mutableStateOf(...)` is a delegated property, and the delegate object itself is only created **in the initializer on the line where it's declared**. If a method called from `init {}` accesses this property on a background thread while the delegate object doesn't exist yet, that's an NPE.

Java's object initialization order is "fields → init blocks → constructor body", and Kotlin is similar — but the moment a background thread pops up in the middle, that order stops being reliable. **Conclusion: any state property that can be written from init or a background thread must be declared before init.** This lesson applies to every class that starts threads during construction.

---

## Chapter 6: Build and Pitfalls

### 6.1 Dependency Configuration

```kotlin
android {
    namespace = "com.ryu.sprdflasher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ryu.sprdflasher"
        minSdk = 23
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0-rz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    // Official liquid glass library
    implementation("io.github.kyant0:backdrop:2.0.1")
    // The Catalog's Capsule shape comes from this library (official direct dependency)
    implementation("io.github.kyant0:shapes:1.2.1")

    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-graphics:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.runtime:runtime:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.activity:activity-compose:1.11.0")
}
```

One thing to watch on versions: the POM for official 2.0.1 declares a Compose version of **1.12.0**. If you use an off-the-shelf template (usually pinned to a stable combo like Compose BOM plus an older Kotlin), you'll resolve into a conflict. **Aligning Compose to 1.12.0** is the least painful fix.

### 6.2 Pitfall: Compose 1.12.0 Hard-Requires minSdk 23

I mentioned this pitfall earlier; let me expand on it here, because it's very representative.

I wanted to support Android 5.0 (SDK 21), for a very practical reason: some old Spreadtrum machines are stuck on 5.0.

First idea was the old trick of adding `tools:overrideLibrary`:

```xml
<uses-sdk tools:overrideLibrary="androidx.compose.animation.core,
                                 androidx.compose.animation,
                                 androidx.compose.foundation.layout,
                                 androidx.compose.ui.unit" />
```

**Didn't work.** Because the manifest override only bypasses AAPT's **resource** minSdk check, while the `minSdk` of these Compose libraries is baked into the **AAR's bytecode and class file versions**. Even if you fool AAPT, by the dex stage and at runtime, `java.lang.VerifyError` will still find you.

The four libraries that block you specifically:

```
androidx.compose.animation.core
androidx.compose.animation
androidx.compose.foundation.layout
androidx.compose.ui.unit
```

Their `minSdk` is all **23**.

There's also a technical reason here: Compose has used quite a few Java 8+ language features since late 1.x (default methods, `invoke-dynamic` optimizations), and desugaring support for those below API 23 is conditional, so officially 23 was chosen as the safety line.

**Conclusion: minSdk is 23.** There's no other way. Android 5.0 users just have to keep using the old version.

### 6.3 Pitfall: Non-ASCII (Chinese) Paths Break the Build

This one has nothing to do with Android, but it blocked me for a long time.

All three toolchains — `aapt2`, `javac`, and `gradle` — **do not support non-ASCII (Chinese) paths**. The same resources compile fine under an ASCII path, but under `D:\123\测试中文\` they immediately report:

```
failed to open directory
```

And the error message points at an internal temp directory, so it's hard to realize right away that the path is the problem.

The old project (the AIDE one) solved this with `build_apk.py`: the script mirrors the project into a pure-ASCII temp directory, builds there, and copies the artifacts back.

```python
# Rough logic
WORK = Path(tempfile.gettempdir()) / "sprd_build_ascii"   # pure ASCII
shutil.copytree(SRC_PROJECT, WORK, dirs_exist_ok=True)
# → aapt2 compiles resources → javac → d8 → zipalign → apksigner
# → copy back to the original directory
```

For the Compose version I did two things:

1. Make the project directory name itself ASCII: `展讯-compose` → mirrored into an ASCII directory for the actual build
2. **Wrote a static check script, `compose_check.py`**, that runs before the build

The second one matters more than the first. Because when the build environment itself is unreliable (paths, network, SDK versions can all go wrong), **you need a way to verify the code's correctness that doesn't depend on the build**.

`compose_check.py` does three things:

```python
# 1. Does every com.kyant.backdrop import really exist in the 2.0.1 sources?
KNOWN = {
    "Backdrop", "LayerBackdrop", "rememberLayerBackdrop", "layerBackdrop",
    "drawBackdrop", "drawPlainBackdrop", "blur", "lens",
    "Highlight", "Shadow", "InnerShadow", "HighlightStyle",
}

# 2. Kotlin brace balance (count after stripping comments and strings)
def check_braces(name, src): ...

# 3. Are the named arguments of drawBackdrop / lens / blur valid?
DRAW_BACKDROP_PARAMS = {
    "backdrop", "shape", "effects", "highlight", "shadow", "innerShadow",
    "layerBlock", "exportedBackdrop", "onDrawBehind", "onDrawBackdrop",
    "onDrawSurface", "onDrawFront",
}
LENS_PARAMS = {"refractionHeight", "refractionAmount", "depthEffect", "chromaticAberration"}
BLUR_PARAMS = {"radius", "edgeTreatment"}
```

Item 3 saved me once: if a `lens()` parameter name is wrong (say you write `chromatic` instead of `chromaticAberration`), the Kotlin compiler reports "no named parameter found", but inside a pile of Compose generic inference errors that message is almost impossible to locate. Checking with the script first gives you an answer in 5 seconds.

### 6.4 Pitfall: AIDE Can't Compile Compose

The original project was built with AIDE (an on-device IDE); the whole project was just `app/src/main/java` + `app/src/main/res`, no Gradle needed.

After rewriting to Compose, AIDE was simply out:

- AIDE doesn't support the Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`)
- AIDE doesn't support Kotlin DSL (`build.gradle.kts`)
- AIDE can't handle Maven libraries like `io.github.kyant0:backdrop:2.0.1` that need transitive dependency resolution

**Conclusion: the Compose version has to be built on a PC.** This is an irreversible trade-off — you can't both enjoy modern Compose and keep the convenience of on-device compilation.

### 6.5 Other Small Pitfalls

**`getDeviceId()` conflict.** `ContextWrapper` added a new `final` method with the same name in API 33, so if you also define `getDeviceId()` in a custom View or Manager, compilation fails (you can't override a final method). Just rename it to `getLocalDeviceId()` — and don't change it back.

**Broadcast registration on SDK 33.** `registerReceiver` requires an explicit export flag on API 33+:

```kotlin
if (android.os.Build.VERSION.SDK_INT >= 33) {
    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
} else {
    context.registerReceiver(usbReceiver, filter)
}
```

**PendingIntent mutability on API 31.** USB permission requests must use `FLAG_MUTABLE`, otherwise the system can't stuff the device info into the Intent:

```kotlin
val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
```

**Storage permission tiers.** Android 11+ needs `MANAGE_EXTERNAL_STORAGE` and a jump to the settings page to request it; Android 6~10 uses runtime permissions. A flashing tool has to read and write arbitrary paths (PAC files, firmware, partition images), so this permission is unavoidable.

---

## Epilogue: On Trade-offs

Having written all this, let me list the most important decisions in this project, because the technical details will go stale but the reasons behind the decisions won't.

**First, choosing Compose meant losing AIDE.**

This isn't a multiple-choice question of "do I want a modern UI"; it's the inevitable consequence of "do I use the official liquid glass library". Since 2.0 the official library is Compose Multiplatform only, with no View-based API. If you want the official look, you have to go Compose; if you go Compose, you have to build on a PC.

Incidentally: in the old project I had actually written a home-grown View-based liquid glass engine (`LiquidGlassEngine.java` + `LiquidGlassView.java`) that replicated AGSL refraction, dispersion, and blur. But it had a fatal flaw — the View version can only capture the background by "software-drawing the window into a small bitmap", which has about 120ms of delayed refresh while scrolling; whereas the Compose version uses `GraphicsLayer`, which is genuine GPU recording. **This is the gap between two generations of technology, not something effort can close.**

**Second, choosing real-time refraction meant accepting dropped frames.**

The entire Chapter 4 is about this. I tried merging cards (the user didn't want it), pre-baking the wallpaper (it turned into a mosaic), turning refraction off while scrolling (it flickered), and in the end found that these approaches all either change the requirements, change the visuals, or change the feel — **none of them actually solves the performance problem while keeping the effect**.

So the solution I kept (`LazyColumn` + degrading button `lens` during scrolling) is a very small optimization, because it was never meant to be the point. The point is admitting: **the cost of this effect is real**.

**Third, the engine wasn't touched.**

The UI could be completely overthrown and rewritten from Java + XML to Kotlin + Compose, but not a single character of that 152KB `UsbFlashEngine.java` changed. The role of `FlashEngineBridge` is very clear — it's a translation layer, not a logic layer.

That decision saved a huge amount of time during the rewrite: if the UI misbehaves, look at the UI; if flashing misbehaves, look at the engine; the two never contaminate each other. And however the client UI is rewritten, it can't affect whether flashing is correct — **and for a tool that gets used to handle hardware data, that matters more than anything else.**

---

**One last reflection.**

The moment the liquid glass effect first worked really did feel like an achievement. But two months in, my favorite parts aren't the refraction at all — they're the over-damped spring at `dampingRatio = 1.0` in `DampedDragAnimation`, the four `main.post { }` lines in the Bridge, and the crudely pragmatic `compose_check.py` script.

Pretty things are what people remember at a glance, but what makes people willing to keep using something long-term is the parts that don't go wrong.

---

*Project: `com.ryu.sprdflasher` v1.0.0-rz*
*Liquid glass library: [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 2.0.1*
*FDL flow reference: [ilyakurdyukov/spreadtrum_flash](https://github.com/ilyakurdyukov/spreadtrum_flash)*
