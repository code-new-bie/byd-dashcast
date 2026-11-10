#include "projection_background_controller.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

#ifdef NDEBUG
#error Controller tests require assertions
#endif

/* This is a fake render port. It is deliberately NOT a Kanzi/Screen adapter. */
typedef struct {
    bool mainThread;
    bool inspection;
    bool outputAvailable;
    bool applySuccess;
    bool restoreSuccess;
    bool withdrawSuccess;
    bool suppressed;
    bool videoVisible;
    uint32_t readiness;
    uint64_t sceneEpoch;
    uint64_t serial;
    uint64_t outputTime;
    unsigned int applyCalls;
    unsigned int restoreCalls;
    unsigned int withdrawCalls;
    unsigned int observeCalls;
    unsigned int desiredTheme;
    unsigned int renderedTheme;
    unsigned int operationOrder;
    unsigned int withdrawOrder;
    unsigned int restoreOrder;
    bool reenter;
    DcBgController *controller;
    DcBgResult reentryResult;
} FakePort;

typedef struct {
    FakePort fake;
    DcBgController controller;
    DcBgSession session;
} Fixture;

static bool onMainThread(void *context) { return ((FakePort *)context)->mainThread; }

static bool inspect(void *context, DcBgReadiness *readiness) {
    FakePort *fake = context;
    readiness->sceneEpoch = fake->sceneEpoch;
    readiness->verified = fake->readiness;
    return fake->inspection;
}

static bool observe(void *context, DcBgSession session, DcBgOutput *output) {
    FakePort *fake = context;
    assert(session.receiverPid != 0 && session.hmiEpoch != 0 && session.generation != 0);
    fake->observeCalls++;
    output->serial = fake->serial;
    output->monotonicMs = fake->outputTime;
    return fake->outputAvailable;
}

static bool suppress(void *context, uint64_t sceneEpoch) {
    FakePort *fake = context;
    assert(fake->mainThread && sceneEpoch == fake->sceneEpoch);
    fake->applyCalls++;
    if (fake->reenter) {
        fake->reentryResult = dc_bg_tick(fake->controller, 110);
    }
    /* Even failed callbacks may have partially changed the backing. */
    fake->suppressed = true;
    return fake->applySuccess;
}

static bool restore(void *context) {
    FakePort *fake = context;
    assert(fake->mainThread);
    fake->restoreCalls++;
    fake->restoreOrder = ++fake->operationOrder;
    if (fake->restoreSuccess) {
        fake->suppressed = false;
        fake->renderedTheme = fake->desiredTheme;
    }
    return fake->restoreSuccess;
}

static bool withdraw(void *context, DcBgSession session) {
    FakePort *fake = context;
    assert(fake->mainThread && session.receiverPid != 0);
    fake->withdrawCalls++;
    fake->withdrawOrder = ++fake->operationOrder;
    if (fake->withdrawSuccess) {
        fake->videoVisible = false;
    }
    return fake->withdrawSuccess;
}

static const DcBgPort PORT = {onMainThread, inspect, observe, suppress, restore, withdraw};

static void initialize(Fixture *fixture) {
    memset(fixture, 0, sizeof(*fixture));
    fixture->fake.mainThread = true;
    fixture->fake.inspection = true;
    fixture->fake.outputAvailable = true;
    fixture->fake.applySuccess = true;
    fixture->fake.restoreSuccess = true;
    fixture->fake.withdrawSuccess = true;
    fixture->fake.readiness = DC_BG_READY_ALL;
    fixture->fake.sceneEpoch = 7;
    fixture->fake.serial = 1;
    fixture->fake.outputTime = 110;
    fixture->fake.desiredTheme = 1;
    fixture->fake.renderedTheme = 1;
    fixture->fake.controller = &fixture->controller;
    assert(dc_bg_init(&fixture->controller, &PORT, &fixture->fake, 900) == DC_BG_OK);
}

static void prepare(Fixture *fixture) {
    assert(dc_bg_prepare(&fixture->controller, 42, 100, &fixture->session) == DC_BG_OK);
}

static void activate(Fixture *fixture) {
    prepare(fixture);
    fixture->fake.videoVisible = true;
    assert(dc_bg_commit(&fixture->controller, fixture->session, 110) == DC_BG_OK);
}

static void coldStart(void) {
    Fixture f;
    initialize(&f);
    assert(f.controller.state == DC_BG_NATIVE && !f.controller.suppressed);
    assert(dc_bg_tick(&f.controller, 0) == DC_BG_OK);
    assert(f.fake.applyCalls == 0 && f.fake.withdrawCalls == 0 && f.fake.restoreCalls == 0);
}

static void unavailablePort(void) {
    Fixture f;
    DcBgPort port = PORT;
    initialize(&f);
    assert(dc_bg_init(&f.controller, NULL, &f.fake, 1) == DC_BG_INVALID);
    assert(dc_bg_tick(&f.controller, 1) == DC_BG_INVALID);
    port.restoreBackground = NULL;
    assert(dc_bg_init(&f.controller, &port, &f.fake, 1) == DC_BG_INVALID);
    assert(dc_bg_init(&f.controller, &PORT, &f.fake, 0) == DC_BG_INVALID);
    assert(dc_bg_init(NULL, &PORT, &f.fake, 1) == DC_BG_INVALID);
}

static void invalidRequest(void) {
    Fixture f;
    initialize(&f);
    assert(dc_bg_prepare(&f.controller, 0, 100, &f.session) == DC_BG_INVALID);
    assert(dc_bg_prepare(&f.controller, 42, 100, NULL) == DC_BG_INVALID);
    assert(f.controller.state == DC_BG_NATIVE);
}

static void readinessBits(void) {
    uint32_t bit;
    for (bit = 1; bit <= DC_BG_READY_PAGE; bit <<= 1) {
        Fixture f;
        initialize(&f);
        f.fake.readiness &= ~bit;
        assert(dc_bg_prepare(&f.controller, 42, 100, &f.session) == DC_BG_NOT_READY);
        assert(f.fake.applyCalls == 0 && f.controller.state == DC_BG_NATIVE);
    }
}

static void unknownReadiness(void) {
    Fixture f;
    initialize(&f);
    f.fake.readiness |= UINT32_C(32);
    assert(dc_bg_prepare(&f.controller, 42, 100, &f.session) == DC_BG_NOT_READY);
    f.fake.readiness = DC_BG_READY_ALL;
    f.fake.sceneEpoch = 0;
    assert(dc_bg_prepare(&f.controller, 42, 100, &f.session) == DC_BG_NOT_READY);
    f.fake.sceneEpoch = 7;
    f.fake.inspection = false;
    assert(dc_bg_prepare(&f.controller, 42, 100, &f.session) == DC_BG_NOT_READY);
}

static void prepareKeepsNative(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    assert(f.controller.state == DC_BG_PREPARING && !f.fake.suppressed);
    assert(f.fake.applyCalls == 0 && f.fake.observeCalls == 0);
    assert(dc_bg_prepare(&f.controller, 43, 101, &f.session) == DC_BG_BUSY);
}

static void normalLifecycleLatestTheme(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    assert(f.controller.state == DC_BG_ACTIVE && f.fake.suppressed);
    f.fake.desiredTheme = 3;
    f.fake.serial = 2;
    f.fake.outputTime = 200;
    assert(dc_bg_renew(&f.controller, f.session, 200) == DC_BG_OK);
    assert(dc_bg_restore(&f.controller, f.session, 210) == DC_BG_OK);
    assert(f.controller.state == DC_BG_NATIVE && !f.fake.suppressed && !f.fake.videoVisible);
    assert(f.fake.renderedTheme == 3 && f.fake.withdrawOrder < f.fake.restoreOrder);
    assert(dc_bg_restore(&f.controller, f.session, 211) == DC_BG_OK);
    assert(f.fake.restoreCalls == 1);
}

static void expiredPreparation(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    assert(dc_bg_commit(&f.controller, f.session, 600) == DC_BG_EXPIRED);
    assert(f.fake.applyCalls == 0 && f.controller.state == DC_BG_NATIVE);
}

static void preparationTick(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    assert(dc_bg_tick(&f.controller, 599) == DC_BG_OK);
    assert(dc_bg_tick(&f.controller, 600) == DC_BG_EXPIRED);
}

static void missingOutput(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.outputAvailable = false;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_NO_OUTPUT);
    assert(f.fake.applyCalls == 0 && !f.fake.suppressed);
}

static void expiredOutput(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.outputTime = 100;
    assert(dc_bg_commit(&f.controller, f.session, 350) == DC_BG_NO_OUTPUT);
}

static void futureOutput(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.outputTime = 111;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_NO_OUTPUT);
}

static void zeroSerial(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.serial = 0;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_NO_OUTPUT);
}

static void leaseExpiresDespiteFreshOutput(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.serial = 50;
    f.fake.outputTime = 1110;
    assert(dc_bg_tick(&f.controller, 1110) == DC_BG_EXPIRED);
    assert(f.controller.state == DC_BG_NATIVE && !f.fake.suppressed);
}

static void noRequestStillRestores(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    assert(dc_bg_tick(&f.controller, 359) == DC_BG_OK);
    assert(dc_bg_tick(&f.controller, 360) == DC_BG_NO_OUTPUT);
    assert(f.controller.state == DC_BG_NATIVE && !f.fake.suppressed);
}

static void heartbeatCannotRenew(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    assert(dc_bg_renew(&f.controller, f.session, 120) == DC_BG_NO_OUTPUT);
    assert(f.controller.state == DC_BG_NATIVE);
}

static void falsifiedFreshTimestamp(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.outputTime = 120;
    assert(dc_bg_tick(&f.controller, 120) == DC_BG_NO_OUTPUT);
}

static void regressingOutput(void) {
    Fixture f;
    initialize(&f);
    f.fake.serial = 3;
    activate(&f);
    f.fake.serial = 2;
    f.fake.outputTime = 120;
    assert(dc_bg_tick(&f.controller, 120) == DC_BG_NO_OUTPUT);
}

static void regressingOutputTime(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.serial = 2;
    f.fake.outputTime = 109;
    assert(dc_bg_tick(&f.controller, 120) == DC_BG_NO_OUTPUT);
}

static void ticksDoNotConsumeRenewEvidence(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.serial = 2;
    f.fake.outputTime = 150;
    assert(dc_bg_tick(&f.controller, 150) == DC_BG_OK);
    assert(dc_bg_renew(&f.controller, f.session, 160) == DC_BG_OK);
    assert(f.controller.deadline == 1160);
}

static void sceneReload(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.sceneEpoch++;
    f.fake.desiredTheme = 9;
    assert(dc_bg_tick(&f.controller, 120) == DC_BG_NOT_READY);
    assert(f.fake.renderedTheme == 9 && !f.fake.suppressed);
}

static void unsupportedPage(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.readiness &= ~DC_BG_READY_PAGE;
    assert(dc_bg_tick(&f.controller, 120) == DC_BG_NOT_READY);
    assert(!f.fake.videoVisible && !f.fake.suppressed);
}

static void partialApply(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.applySuccess = false;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_APPLY_FAILED);
    assert(f.fake.restoreCalls == 1 && !f.fake.suppressed && f.controller.state == DC_BG_NATIVE);
}

static void restoreFailureAndExplicitRecovery(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.restoreSuccess = false;
    assert(dc_bg_restore(&f.controller, f.session, 120) == DC_BG_RESTORE_FAILED);
    assert(f.controller.state == DC_BG_BLOCKED && f.controller.suppressed);
    assert(dc_bg_prepare(&f.controller, 42, 121, &f.session) == DC_BG_BUSY);
    assert(dc_bg_tick(&f.controller, 122) == DC_BG_BUSY);
    assert(dc_bg_restore(&f.controller, f.session, 123) == DC_BG_BUSY);
    f.fake.restoreSuccess = true;
    assert(dc_bg_recover(&f.controller, f.session, 124) == DC_BG_OK);
    assert(f.controller.state == DC_BG_NATIVE && !f.fake.suppressed);
}

static void failedApplyAndRestoreRemainUnknown(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.applySuccess = false;
    f.fake.restoreSuccess = false;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_RESTORE_FAILED);
    assert(f.controller.state == DC_BG_BLOCKED && f.controller.suppressed && f.fake.suppressed);
}

static void reentrantCallbackIsRejected(void) {
    Fixture f;
    initialize(&f);
    f.fake.reenter = true;
    activate(&f);
    assert(f.fake.reentryResult == DC_BG_BUSY && f.controller.state == DC_BG_ACTIVE);
    assert(!f.controller.inOperation && f.fake.restoreCalls == 0);
}

static void notReadyAtCommitDoesNotHide(void) {
    Fixture f;
    initialize(&f);
    prepare(&f);
    f.fake.readiness &= ~DC_BG_READY_ALPHA;
    assert(dc_bg_commit(&f.controller, f.session, 110) == DC_BG_NOT_READY);
    assert(f.fake.applyCalls == 0 && !f.fake.suppressed);
}

static void withdrawFailureStillRestores(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.withdrawSuccess = false;
    assert(dc_bg_restore(&f.controller, f.session, 120) == DC_BG_RESTORE_FAILED);
    assert(f.controller.state == DC_BG_BLOCKED && !f.controller.suppressed && !f.fake.suppressed);
    assert(f.fake.videoVisible && f.fake.restoreCalls == 1);
    f.fake.withdrawSuccess = true;
    assert(dc_bg_recover(&f.controller, f.session, 121) == DC_BG_OK);
}

static void staleOwnerAndGeneration(void) {
    Fixture f;
    DcBgSession stale;
    initialize(&f);
    activate(&f);
    stale = f.session;
    stale.receiverPid++;
    assert(dc_bg_restore(&f.controller, stale, 0) == DC_BG_STALE_SESSION);
    stale = f.session;
    stale.generation++;
    assert(dc_bg_renew(&f.controller, stale, 0) == DC_BG_STALE_SESSION);
    stale = f.session;
    stale.hmiEpoch++;
    assert(dc_bg_commit(&f.controller, stale, 0) == DC_BG_STALE_SESSION);
    assert(f.fake.suppressed && f.fake.restoreCalls == 0 && f.controller.lastNow == 110);
}

static void repeatedSessionDoesNotAffectNew(void) {
    Fixture f;
    DcBgSession stale;
    initialize(&f);
    activate(&f);
    stale = f.session;
    assert(dc_bg_restore(&f.controller, f.session, 120) == DC_BG_OK);
    assert(dc_bg_prepare(&f.controller, 42, 130, &f.session) == DC_BG_OK);
    f.fake.outputTime = 140;
    assert(dc_bg_commit(&f.controller, f.session, 140) == DC_BG_OK);
    assert(f.session.generation > stale.generation);
    assert(dc_bg_restore(&f.controller, stale, 150) == DC_BG_STALE_SESSION);
    assert(f.fake.suppressed && f.controller.state == DC_BG_ACTIVE);
}

static void wrongThreadDoesNothing(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    f.fake.mainThread = false;
    assert(dc_bg_tick(&f.controller, 2000) == DC_BG_WRONG_THREAD);
    assert(dc_bg_commit(&f.controller, f.session, 120) == DC_BG_WRONG_THREAD);
    assert(dc_bg_renew(&f.controller, f.session, 120) == DC_BG_WRONG_THREAD);
    assert(dc_bg_restore(&f.controller, f.session, 120) == DC_BG_WRONG_THREAD);
    assert(dc_bg_recover(&f.controller, f.session, 120) == DC_BG_WRONG_THREAD);
    assert(dc_bg_prepare(&f.controller, 42, 120, &f.session) == DC_BG_WRONG_THREAD);
    assert(f.fake.restoreCalls == 0 && f.fake.applyCalls == 1 && f.controller.lastNow == 110);
    f.fake.mainThread = true;
    assert(dc_bg_tick(&f.controller, 2000) == DC_BG_EXPIRED);
}

static void clockRollbackBlocks(void) {
    Fixture f;
    initialize(&f);
    activate(&f);
    assert(dc_bg_tick(&f.controller, 109) == DC_BG_CLOCK_ERROR);
    assert(f.controller.state == DC_BG_BLOCKED && !f.fake.suppressed);
    assert(dc_bg_recover(&f.controller, f.session, 109) == DC_BG_CLOCK_ERROR);
    assert(dc_bg_recover(&f.controller, f.session, 120) == DC_BG_OK);
}

static void deadlineOverflow(void) {
    Fixture f;
    initialize(&f);
    assert(dc_bg_prepare(&f.controller, 42, UINT64_MAX, &f.session) == DC_BG_CLOCK_ERROR);
    assert(f.fake.applyCalls == 0 && f.controller.state == DC_BG_BLOCKED);
}

static void generationNeverWraps(void) {
    Fixture f;
    initialize(&f);
    f.controller.nextGeneration = UINT64_MAX;
    assert(dc_bg_prepare(&f.controller, 42, 100, &f.session) == DC_BG_GENERATION_EXHAUSTED);
    assert(f.controller.state == DC_BG_BLOCKED);
}

static void repeatedLifecycle(void) {
    Fixture f;
    unsigned int index;
    uint64_t previous = 0;
    initialize(&f);
    for (index = 0; index < 1000; index++) {
        uint64_t now = UINT64_C(100) + (uint64_t)index * UINT64_C(100);
        assert(dc_bg_prepare(&f.controller, 42, now, &f.session) == DC_BG_OK);
        assert(f.session.generation > previous);
        previous = f.session.generation;
        f.fake.serial++;
        f.fake.outputTime = now + 1;
        assert(dc_bg_commit(&f.controller, f.session, now + 1) == DC_BG_OK);
        f.fake.desiredTheme = index;
        assert(dc_bg_restore(&f.controller, f.session, now + 2) == DC_BG_OK);
        assert(f.fake.renderedTheme == index && !f.fake.suppressed);
    }
    assert(f.fake.applyCalls == 1000 && f.fake.restoreCalls == 1000 && f.fake.withdrawCalls == 1000);
}

typedef struct { const char *name; void (*run)(void); } Test;

int main(void) {
    static const Test tests[] = {
        {"cold_start", coldStart}, {"missing_port", unavailablePort}, {"invalid_request", invalidRequest},
        {"required_capabilities", readinessBits}, {"unknown_readiness", unknownReadiness},
        {"prepare_keeps_native", prepareKeepsNative}, {"normal_latest_theme", normalLifecycleLatestTheme},
        {"prepare_expiry", expiredPreparation}, {"prepare_tick", preparationTick},
        {"missing_output", missingOutput}, {"stale_output", expiredOutput}, {"future_output", futureOutput},
        {"zero_serial", zeroSerial}, {"lease_expiry", leaseExpiresDespiteFreshOutput},
        {"no_request_recovery", noRequestStillRestores}, {"heartbeat_is_not_output", heartbeatCannotRenew},
        {"false_output_time", falsifiedFreshTimestamp}, {"regressing_output", regressingOutput},
        {"regressing_output_time", regressingOutputTime}, {"tick_and_renew", ticksDoNotConsumeRenewEvidence},
        {"scene_reload", sceneReload}, {"unsupported_page", unsupportedPage}, {"partial_apply", partialApply},
        {"restore_failure", restoreFailureAndExplicitRecovery}, {"withdraw_failure", withdrawFailureStillRestores},
        {"partial_restore_unknown", failedApplyAndRestoreRemainUnknown},
        {"reentrant_callback", reentrantCallbackIsRejected}, {"commit_not_ready", notReadyAtCommitDoesNotHide},
        {"wrong_session", staleOwnerAndGeneration}, {"old_stop_new_session", repeatedSessionDoesNotAffectNew},
        {"wrong_thread", wrongThreadDoesNothing}, {"clock_rollback", clockRollbackBlocks},
        {"deadline_overflow", deadlineOverflow}, {"generation_overflow", generationNeverWraps},
        {"1000_lifecycles", repeatedLifecycle}
    };
    size_t index;
    for (index = 0; index < sizeof(tests) / sizeof(tests[0]); index++) {
        tests[index].run();
        printf("PASS %s\n", tests[index].name);
    }
    printf("TESTS_PASSED=%zu\n", sizeof(tests) / sizeof(tests[0]));
    return 0;
}
