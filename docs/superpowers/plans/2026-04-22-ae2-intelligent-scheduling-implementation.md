# AE2-IntelligentScheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a GTNH addon mod that adds a `智能合成` button to the AE2 craft-confirm UI, analyzes the full AE2 crafting tree, splits large intermediate/final deficits into 1/2/4/8/16 programmatic jobs by size tier, and executes them layer-by-layer across available crafting CPUs.

**Architecture:** Keep AE2 responsible for single-job simulation and execution, while this mod owns planning, threshold splitting, order state, task scheduling, and UI. Read the crafted job from AE2's existing `ContainerCraftConfirm`, traverse `CraftingJobV2` / `CraftingRequest` resolver trees into our own immutable planning model, then submit tracked jobs back through `ICraftingGrid.submitJob(...)` using a dedicated `ICraftingRequester` bridge.

**Tech Stack:** Minecraft 1.7.10, Forge, GTNH convention plugin, AE2 Unofficial GTNH API/runtime, Sponge Mixin, JUnit 5

---

## Assumptions Locked For This Plan

- `MOD_ID = ae2intelligentscheduling`
- `MOD_ID_LOWER = ae2intelligentscheduling`
- `ROOT_PACKAGE = com.homeftw.ae2intelligentscheduling`
- `MOD_CLASS_NAME = AE2IntelligentScheduling`
- External source reference root: `D:\Code\GTNH LIB\Applied-Energistics-2-Unofficial-rv3-beta-695-GTNH`

If naming must change, do it in Task 1 before any Java package is created.

## File Structure Map

### Project bootstrap

- `build.gradle`
- `settings.gradle`
- `gradle.properties`
- `dependencies.gradle`
- `addon.gradle`
- `repositories.gradle`
- `src/main/resources/mixins.ae2intelligentscheduling.json`
- `src/main/resources/assets/ae2intelligentscheduling/lang/en_US.lang`

### Mod entry / lifecycle

- `src/main/java/com/homeftw/ae2intelligentscheduling/AE2IntelligentScheduling.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/CommonProxy.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/ClientProxy.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/config/Config.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/network/NetworkHandler.java`

### AE2 integration

- `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/GuiCraftConfirmMixin.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/ContainerCraftConfirmAccessor.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/ContainerCraftConfirmInvoker.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftingJobSnapshotFactory.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftTreeWalker.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CpuSelector.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftSubmitter.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2RequestKey.java`

### Smart-craft domain

- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftNode.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftTask.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftLayer.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftOrder.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftStatus.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftSplitPlanner.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftOrderBuilder.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftOrderManager.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftScheduler.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftRequesterBridge.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftStockVerifier.java`

### Packets / GUI

- `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/OpenSmartCraftPreviewPacket.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/RequestSmartCraftActionPacket.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/SyncSmartCraftOrderPacket.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/client/gui/GuiSmartCraftStatus.java`
- `src/main/java/com/homeftw/ae2intelligentscheduling/client/gui/widget/SmartCraftTaskList.java`

### Tests

- `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftSplitPlannerTest.java`
- `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftOrderBuilderTest.java`
- `src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftTreeWalkerTest.java`
- `src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CpuSelectorTest.java`
- `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftSchedulerTest.java`
- `src/test/java/com/homeftw/ae2intelligentscheduling/network/packet/SmartCraftPacketCodecTest.java`

### External AE2 references to read while implementing

- `appeng/client/gui/implementations/GuiCraftConfirm.java`
- `appeng/container/implementations/ContainerCraftConfirm.java`
- `appeng/core/sync/packets/PacketValueConfig.java`
- `appeng/api/networking/crafting/ICraftingGrid.java`
- `appeng/helpers/MultiCraftingTracker.java`
- `appeng/crafting/v2/CraftingJobV2.java`
- `appeng/crafting/v2/CraftingRequest.java`
- `appeng/crafting/v2/resolvers/CraftableItemResolver.java`

---

### Task 1: Bootstrap The GTNH Mod Project

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `dependencies.gradle`
- Create: `addon.gradle`
- Create: `repositories.gradle`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/AE2IntelligentScheduling.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/CommonProxy.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/ClientProxy.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/config/Config.java`
- Create: `src/main/resources/mixins.ae2intelligentscheduling.json`

- [ ] **Step 1: Scaffold the standard GTNH build files with locked metadata**

```properties
# gradle.properties
modName=AE2-IntelligentScheduling
modId=ae2intelligentscheduling
modGroup=com.homeftw.ae2intelligentscheduling
autoUpdateBuildScript=false
enableMixins=true
mixinsPackage=com.homeftw.ae2intelligentscheduling.mixin
usesMixins=true
```

```groovy
// dependencies.gradle
dependencies {
    implementation(rfg.deobf("appeng:appliedenergistics2-unofficial:rv3-beta-695-GTNH:dev"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
```

- [ ] **Step 2: Add the minimal mod entry and proxy wiring**

```java
@Mod(
        modid = Tags.MODID,
        name = "AE2-IntelligentScheduling",
        version = Tags.VERSION,
        dependencies = "required-after:appliedenergistics2")
public class AE2IntelligentScheduling {

    @SidedProxy(
            clientSide = "com.homeftw.ae2intelligentscheduling.ClientProxy",
            serverSide = "com.homeftw.ae2intelligentscheduling.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
```

- [ ] **Step 3: Copy the Gradle wrapper from an existing GTNH project and verify compile**

Run:

```powershell
Copy-Item D:\Code\GTstaff\gradlew D:\Code\AE2-IntelligentScheduling\gradlew
Copy-Item D:\Code\GTstaff\gradlew.bat D:\Code\AE2-IntelligentScheduling\gradlew.bat
Copy-Item D:\Code\GTstaff\gradle\wrapper\* D:\Code\AE2-IntelligentScheduling\gradle\wrapper\
./gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add build.gradle settings.gradle gradle.properties dependencies.gradle addon.gradle repositories.gradle gradlew gradlew.bat gradle src/main
git commit -m "feat: scaffold AE2 intelligent scheduling mod"
```

### Task 2: Build The Pure Smart-Craft Planning Model

**Files:**
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftNode.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftTask.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftLayer.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftOrder.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/model/SmartCraftStatus.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftSplitPlanner.java`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftSplitPlannerTest.java`

- [ ] **Step 1: Write the failing split-planner tests for the `1g / 4g / 16g / 64g` thresholds**

```java
@Test
void splits_one_g_into_two_tasks() {
    List<Long> parts = SmartCraftSplitPlanner.splitAmount(1_000_000_000L);
    assertEquals(List.of(500_000_000L, 500_000_000L), parts);
}

@Test
void splits_sixty_four_g_into_sixteen_tasks_with_exact_sum() {
    List<Long> parts = SmartCraftSplitPlanner.splitAmount(64_000_000_000L);
    assertEquals(16, parts.size());
    assertEquals(64_000_000_000L, parts.stream().mapToLong(Long::longValue).sum());
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftSplitPlannerTest
```

Expected: FAIL with `SmartCraftSplitPlanner` missing

- [ ] **Step 3: Implement the immutable model and minimal split planner**

```java
public final class SmartCraftSplitPlanner {

    public static final long ONE_G = 1_000_000_000L;
    public static final long FOUR_G = 4_000_000_000L;
    public static final long SIXTEEN_G = 16_000_000_000L;
    public static final long SIXTY_FOUR_G = 64_000_000_000L;

    private SmartCraftSplitPlanner() {}

    public static List<Long> splitAmount(long missingAmount) {
        int partitions;
        if (missingAmount >= SIXTY_FOUR_G) {
            partitions = 16;
        } else if (missingAmount >= SIXTEEN_G) {
            partitions = 8;
        } else if (missingAmount >= FOUR_G) {
            partitions = 4;
        } else if (missingAmount >= ONE_G) {
            partitions = 2;
        } else {
            partitions = 1;
        }
        long base = missingAmount / partitions;
        long remainder = missingAmount % partitions;
        List<Long> result = new ArrayList<>(partitions);
        for (int i = 0; i < partitions; i++) {
            result.add(base + (i < remainder ? 1 : 0));
        }
        return result;
    }
}
```

- [ ] **Step 4: Re-run the split-planner test**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftSplitPlannerTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft
git commit -m "feat: add smart craft planning model"
```

### Task 3: Convert AE2 Crafting Trees Into Layered Orders

**Files:**
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2RequestKey.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftTreeWalker.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftingJobSnapshotFactory.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftOrderBuilder.java`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis/SmartCraftOrderBuilderTest.java`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftTreeWalkerTest.java`

- [ ] **Step 1: Write a failing order-builder test for “deduct stock, then split, then layer”**

```java
@Test
void builds_bottom_up_layers_after_stock_deduction() {
    FakeTreeNode ironPlate = node("iron_plate", 1_500_000_000L, 300_000_000L);
    FakeTreeNode machineHull = node("machine_hull", 2L, 0L, ironPlate);

    SmartCraftOrder order = new SmartCraftOrderBuilder().build(machineHull);

    assertEquals(2, order.layers().size());
    assertEquals(2, order.layers().get(0).tasks().size());
    assertEquals("machine_hull", order.layers().get(1).tasks().get(0).requestKey().id());
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilderTest
```

Expected: FAIL with `SmartCraftOrderBuilder` missing

- [ ] **Step 3: Implement the builder on top of an internal tree DTO, then add the thin AE2 walker**

```java
public SmartCraftOrder build(TreeNode root) {
    List<SmartCraftLayer> layers = new ArrayList<>();
    visit(root, 0, layers);
    return SmartCraftOrder.queued(root.requestKey(), root.missingAmount(), layers);
}

private void visit(TreeNode node, int depth, List<SmartCraftLayer> layers) {
    for (TreeNode child : node.children()) {
        visit(child, depth + 1, layers);
    }
    long missing = Math.max(0L, node.requestedAmount() - node.availableAmount());
    if (missing == 0L) {
        return;
    }
    ensureLayer(layers, depth).tasks().addAll(toTasks(node.requestKey(), missing, depth));
}
```

```java
private void walkRequest(CraftingRequest<IAEItemStack> request, List<TreeNode> children) {
    for (UsedResolverEntry<IAEItemStack> entry : request.usedResolvers) {
        if (entry.task instanceof CraftFromPatternTask task) {
            for (CraftingRequest<IAEItemStack> child : task.getChildRequests()) {
                children.add(walk(child));
            }
        }
    }
}
```

- [ ] **Step 4: Run both tree tests**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilderTest --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftTreeWalkerTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2 src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2 src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/analysis
git commit -m "feat: translate AE2 craft trees into layered smart orders"
```

### Task 4: Add The Runtime Scheduler And AE2 Requester Bridge

**Files:**
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftRequesterBridge.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftOrderManager.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftScheduler.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftStockVerifier.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CpuSelector.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CraftSubmitter.java`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2/Ae2CpuSelectorTest.java`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime/SmartCraftSchedulerTest.java`

- [ ] **Step 1: Write failing tests for CPU selection and layer gating**

```java
@Test
void waits_when_no_matching_cpu_is_idle() {
    SmartCraftTask task = task("processor", 1_000_000_000L, 2);
    SchedulerTick tick = scheduler.tick(List.of(task), List.of(busyCpu(), busyCpu()));
    assertEquals(WAITING_CPU, tick.updatedTask(task.id()).status());
}

@Test
void does_not_start_parent_layer_before_children_done() {
    SmartCraftOrder order = orderWithTwoLayers(childRunning(), parentPending());
    scheduler.tick(order);
    assertEquals(PENDING, order.layers().get(1).tasks().get(0).status());
}
```

- [ ] **Step 2: Run the scheduler tests and verify failure**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelectorTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest
```

Expected: FAIL with scheduler classes missing

- [ ] **Step 3: Implement the runtime state machine and tracked submitter**

```java
public final class SmartCraftRequesterBridge implements ICraftingRequester {

    private final Map<UUID, ICraftingLink> links = new HashMap<>();

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return ImmutableSet.copyOf(links.values());
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        links.values().removeIf(existing -> existing == link || existing.isDone() || existing.isCanceled());
    }
}
```

```java
if (currentLayerDone(order)) {
    order.advanceLayer();
    return;
}
for (SmartCraftTask task : order.currentLayer().tasks()) {
    if (!task.isReadyForSubmission()) {
        continue;
    }
    Optional<ICraftingCPU> cpu = cpuSelector.findIdleCpu(task.requiredCpuCount(), craftingGrid);
    if (cpu.isEmpty()) {
        task.markWaitingCpu();
        continue;
    }
    submitter.submit(task, cpu.get(), requesterBridge, craftingGrid, actionSource);
}
```

- [ ] **Step 4: Re-run the scheduler tests**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelectorTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime src/main/java/com/homeftw/ae2intelligentscheduling/integration/ae2 src/test/java/com/homeftw/ae2intelligentscheduling/integration/ae2 src/test/java/com/homeftw/ae2intelligentscheduling/smartcraft/runtime
git commit -m "feat: add layered smart craft scheduler"
```

### Task 5: Add The AE2 Craft-Confirm Smart Button And Preview Entry Point

**Files:**
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/GuiCraftConfirmMixin.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/ContainerCraftConfirmAccessor.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/mixin/ae2/ContainerCraftConfirmInvoker.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/OpenSmartCraftPreviewPacket.java`
- Modify: `src/main/java/com/homeftw/ae2intelligentscheduling/network/NetworkHandler.java`
- Modify: `src/main/resources/mixins.ae2intelligentscheduling.json`
- Test: `src/test/java/com/homeftw/ae2intelligentscheduling/network/packet/SmartCraftPacketCodecTest.java`

- [ ] **Step 1: Write the failing packet codec test**

```java
@Test
void preview_packet_round_trips_order_id_and_action() {
    OpenSmartCraftPreviewPacket packet = new OpenSmartCraftPreviewPacket();
    ByteBuf buf = Unpooled.buffer();
    packet.toBytes(buf);
    OpenSmartCraftPreviewPacket decoded = new OpenSmartCraftPreviewPacket();
    decoded.fromBytes(buf);
    assertEquals(packet.getOrderId(), decoded.getOrderId());
}
```

- [ ] **Step 2: Run the packet test and verify failure**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest
```

Expected: FAIL with packet class missing

- [ ] **Step 3: Inject the new button into `GuiCraftConfirm` and route it to our own packet**

```java
@Inject(method = "initGui", at = @At("TAIL"))
private void ae2is$addSmartCraftButton(CallbackInfo ci) {
    this.ae2is$smartButton = new GuiButton(
            0xAE21,
            this.guiLeft + this.xSize - 138,
            this.guiTop + this.ySize - 25,
            56,
            20,
            "智能合成");
    this.buttonList.add(this.ae2is$smartButton);
}

@Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
private void ae2is$handleSmartCraftClick(GuiButton btn, CallbackInfo ci) {
    if (btn == this.ae2is$smartButton) {
        NetworkHandler.CHANNEL.sendToServer(new OpenSmartCraftPreviewPacket());
        ci.cancel();
    }
}
```

```java
@Accessor("result")
ICraftingJob ae2is$getResult();

@Invoker("getActionSrc")
BaseActionSource ae2is$invokeGetActionSrc();
```

- [ ] **Step 4: Re-run the packet test**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/homeftw/ae2intelligentscheduling/mixin src/main/java/com/homeftw/ae2intelligentscheduling/network src/main/resources/mixins.ae2intelligentscheduling.json src/test/java/com/homeftw/ae2intelligentscheduling/network/packet
git commit -m "feat: add smart craft button to AE2 craft confirm"
```

### Task 6: Build The Status GUI, Cancel/Retry Actions, And End-To-End Smoke Coverage

**Files:**
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/RequestSmartCraftActionPacket.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/network/packet/SyncSmartCraftOrderPacket.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/client/gui/GuiSmartCraftStatus.java`
- Create: `src/main/java/com/homeftw/ae2intelligentscheduling/client/gui/widget/SmartCraftTaskList.java`
- Modify: `src/main/java/com/homeftw/ae2intelligentscheduling/CommonProxy.java`
- Modify: `src/main/java/com/homeftw/ae2intelligentscheduling/ClientProxy.java`
- Modify: `context.md`
- Modify: `log.md`
- Modify: `ToDOLIST.md`

- [ ] **Step 1: Write a failing test for order-sync packet serialization**

```java
@Test
void sync_packet_round_trips_layer_and_status() {
    SmartCraftOrder order = SmartCraftOrder.testing("processor", RUNNING_LAYER);
    SyncSmartCraftOrderPacket packet = SyncSmartCraftOrderPacket.from(order);
    ByteBuf buf = Unpooled.buffer();
    packet.toBytes(buf);
    SyncSmartCraftOrderPacket decoded = new SyncSmartCraftOrderPacket();
    decoded.fromBytes(buf);
    assertEquals(packet.getStatus(), decoded.getStatus());
    assertEquals(packet.getCurrentLayer(), decoded.getCurrentLayer());
}
```

- [ ] **Step 2: Run the packet test and verify failure**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest
```

Expected: FAIL with sync packet missing

- [ ] **Step 3: Add the status screen and server actions**

```java
public class GuiSmartCraftStatus extends GuiScreen {

    private GuiButton cancelButton;
    private GuiButton retryButton;

    @Override
    public void initGui() {
        this.cancelButton = new GuiButton(1, this.width / 2 - 80, this.height - 40, 70, 20, "取消整单");
        this.retryButton = new GuiButton(2, this.width / 2 + 10, this.height - 40, 70, 20, "重试失败");
        this.buttonList.add(this.cancelButton);
        this.buttonList.add(this.retryButton);
    }
}
```

```java
switch (packet.action()) {
    case CANCEL_ORDER -> orderManager.cancel(packet.orderId(), player);
    case RETRY_FAILED -> orderManager.retryFailedTasks(packet.orderId(), player);
}
```

- [ ] **Step 4: Run targeted tests and a full project test pass**

Run:

```powershell
./gradlew.bat test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest
./gradlew.bat test
```

Expected: PASS

- [ ] **Step 5: Run manual smoke checks and update project docs**

Run:

```powershell
./gradlew.bat runClient
```

Verify in game:

- Open AE2 crafting terminal
- Start a normal craft and confirm the original `Start` path still works
- Open the AE2 craft confirm screen and click `智能合成`
- Confirm the status screen shows layer index, task rows, waiting CPU counts, and cancel/retry buttons
- Submit a large request and confirm sub-jobs wait for lower layers before parent layers

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/homeftw/ae2intelligentscheduling/client src/main/java/com/homeftw/ae2intelligentscheduling/network src/main/java/com/homeftw/ae2intelligentscheduling/CommonProxy.java src/main/java/com/homeftw/ae2intelligentscheduling/ClientProxy.java context.md log.md ToDOLIST.md
git commit -m "feat: add smart craft status UI and control actions"
```

## Self-Review

### Spec coverage

- Preserved AE2 original start button: Task 5
- Added `智能合成` button: Task 5
- Recursive tree analysis: Task 3
- Deduct current AE stock before queue generation: Task 3
- Threshold split rules for `1g / 4g / 16g / 64g`: Task 2
- Layered bottom-up scheduling: Task 4
- Auto-pick free CPUs: Task 4
- Pause/cancel/retry and status UI: Task 6

### Placeholder scan

- No `TBD` / `TODO` / “implement later” placeholders remain in the execution steps
- Every code-changing task includes explicit file paths, code snippets, commands, and expected outcomes

### Type consistency

- Planning types use `SmartCraftNode`, `SmartCraftTask`, `SmartCraftLayer`, `SmartCraftOrder` consistently
- Runtime entry point uses `OpenSmartCraftPreviewPacket`, then `SyncSmartCraftOrderPacket` / `RequestSmartCraftActionPacket`
- AE2 integration is consistently routed through `Ae2CraftTreeWalker`, `Ae2CpuSelector`, and `Ae2CraftSubmitter`
