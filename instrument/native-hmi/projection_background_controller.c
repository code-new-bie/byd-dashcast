#include "projection_background_controller.h"

#include <stddef.h>
#include <string.h>

static bool usable(const DcBgController *controller) {
    return controller != NULL && controller->initialized;
}

static bool sameSession(DcBgSession left, DcBgSession right) {
    return left.hmiEpoch == right.hmiEpoch && left.generation == right.generation
        && left.receiverPid == right.receiverPid && left.generation != 0 && left.receiverPid != 0;
}

static DcBgResult mainThread(DcBgController *controller) {
    if (!usable(controller)) {
        return DC_BG_INVALID;
    }
    if (!controller->port.onMainThread(controller->context)) {
        return DC_BG_WRONG_THREAD;
    }
    return DC_BG_OK;
}

static DcBgResult owned(DcBgController *controller, DcBgSession session) {
    DcBgResult result = mainThread(controller);
    if (result != DC_BG_OK) {
        return result;
    }
    return sameSession(controller->session, session) ? DC_BG_OK : DC_BG_STALE_SESSION;
}

static DcBgResult restoreNow(DcBgController *controller) {
    bool videoGone;
    bool backgroundRestored;
    controller->state = DC_BG_RESTORING;
    /* Still restore foreground's normal backing if withdrawal fails. */
    videoGone = controller->port.withdrawVideo(controller->context, controller->session);
    backgroundRestored = controller->port.restoreBackground(controller->context);
    if (backgroundRestored) {
        controller->suppressed = false;
    }
    controller->state = videoGone && backgroundRestored ? DC_BG_NATIVE : DC_BG_BLOCKED;
    return controller->state == DC_BG_NATIVE ? DC_BG_OK : DC_BG_RESTORE_FAILED;
}

static DcBgResult advanceTime(DcBgController *controller, uint64_t now) {
    if ((controller->timeSeen && now < controller->lastNow) || now > UINT64_MAX - DC_BG_LEASE_MS) {
        if (controller->state == DC_BG_ACTIVE || controller->state == DC_BG_PREPARING) {
            (void)restoreNow(controller);
        }
        controller->state = DC_BG_BLOCKED;
        return DC_BG_CLOCK_ERROR;
    }
    controller->timeSeen = true;
    controller->lastNow = now;
    return DC_BG_OK;
}

static bool ready(DcBgController *controller, bool requireSameScene) {
    DcBgReadiness readiness = {0, 0};
    if (!controller->port.inspect(controller->context, &readiness)
            || readiness.verified != DC_BG_READY_ALL || readiness.sceneEpoch == 0) {
        return false;
    }
    if (requireSameScene && readiness.sceneEpoch != controller->sceneEpoch) {
        return false;
    }
    controller->sceneEpoch = readiness.sceneEpoch;
    return true;
}

static bool freshOutput(DcBgController *controller, uint64_t now, bool requireNew) {
    DcBgOutput output = {0, 0};
    if (!controller->port.observeOutput(controller->context, controller->session, &output)
            || output.serial == 0 || output.monotonicMs > now
            || now - output.monotonicMs >= DC_BG_OUTPUT_MAX_AGE_MS
            || output.serial < controller->outputSerial
            || (output.serial == controller->outputSerial && output.monotonicMs != controller->outputTime)
            || (output.serial > controller->outputSerial && output.monotonicMs < controller->outputTime)
            || (requireNew && output.serial <= controller->renewedSerial)) {
        return false;
    }
    controller->outputSerial = output.serial;
    controller->outputTime = output.monotonicMs;
    return true;
}

static DcBgResult stopFor(DcBgController *controller, DcBgResult reason) {
    DcBgResult result = restoreNow(controller);
    return result == DC_BG_OK ? reason : result;
}

static DcBgResult live(DcBgController *controller, uint64_t now) {
    DcBgResult result = advanceTime(controller, now);
    if (result != DC_BG_OK) {
        return result;
    }
    if (now >= controller->deadline) {
        return stopFor(controller, DC_BG_EXPIRED);
    }
    if (!ready(controller, true)) {
        return stopFor(controller, DC_BG_NOT_READY);
    }
    return DC_BG_OK;
}

DcBgResult dc_bg_init(DcBgController *controller, const DcBgPort *port,
                     void *context, uint64_t hmiEpoch) {
    if (controller == NULL) {
        return DC_BG_INVALID;
    }
    /* Only call on fresh storage; NEVER use init to clear an active/blocked instance. */
    memset(controller, 0, sizeof(*controller));
    controller->state = DC_BG_BLOCKED;
    if (port == NULL || hmiEpoch == 0 || port->onMainThread == NULL || port->inspect == NULL
            || port->observeOutput == NULL || port->suppressBackground == NULL
            || port->restoreBackground == NULL || port->withdrawVideo == NULL) {
        return DC_BG_INVALID;
    }
    controller->port = *port;
    controller->context = context;
    controller->session.hmiEpoch = hmiEpoch;
    controller->nextGeneration = 1;
    controller->initialized = true;
    controller->state = DC_BG_NATIVE;
    return DC_BG_OK;
}

static DcBgResult prepareInternal(DcBgController *controller, uint32_t trustedReceiverPid,
                                  uint64_t localMonotonicMs, DcBgSession *session) {
    DcBgResult result = mainThread(controller);
    if (result != DC_BG_OK) {
        return result;
    }
    if (trustedReceiverPid == 0 || session == NULL) {
        return DC_BG_INVALID;
    }
    if (controller->state != DC_BG_NATIVE) {
        return DC_BG_BUSY;
    }
    result = advanceTime(controller, localMonotonicMs);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->nextGeneration == UINT64_MAX) {
        controller->state = DC_BG_BLOCKED;
        return DC_BG_GENERATION_EXHAUSTED;
    }
    if (!ready(controller, false)) {
        return DC_BG_NOT_READY;
    }
    controller->session.receiverPid = trustedReceiverPid;
    controller->session.generation = controller->nextGeneration++;
    controller->outputSerial = 0;
    controller->outputTime = 0;
    controller->renewedSerial = 0;
    controller->deadline = localMonotonicMs + DC_BG_PREPARE_MS;
    controller->state = DC_BG_PREPARING;
    *session = controller->session;
    return DC_BG_OK;
}

static DcBgResult commitInternal(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    DcBgResult result = owned(controller, session);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state != DC_BG_PREPARING) {
        return DC_BG_BUSY;
    }
    result = live(controller, localMonotonicMs);
    if (result != DC_BG_OK) {
        return result;
    }
    if (!freshOutput(controller, localMonotonicMs, true)) {
        return stopFor(controller, DC_BG_NO_OUTPUT);
    }
    /* Pessimistic until restoration is confirmed, including partial failures. */
    controller->suppressed = true;
    if (!controller->port.suppressBackground(controller->context, controller->sceneEpoch)) {
        /* Failure may mean a partial application: ALWAYS request restoration. */
        return stopFor(controller, DC_BG_APPLY_FAILED);
    }
    controller->suppressed = true;
    controller->renewedSerial = controller->outputSerial;
    controller->deadline = localMonotonicMs + DC_BG_LEASE_MS;
    controller->state = DC_BG_ACTIVE;
    return DC_BG_OK;
}

static DcBgResult renewInternal(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    DcBgResult result = owned(controller, session);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state != DC_BG_ACTIVE) {
        return DC_BG_BUSY;
    }
    result = live(controller, localMonotonicMs);
    if (result != DC_BG_OK) {
        return result;
    }
    if (!freshOutput(controller, localMonotonicMs, true)) {
        return stopFor(controller, DC_BG_NO_OUTPUT);
    }
    controller->renewedSerial = controller->outputSerial;
    controller->deadline = localMonotonicMs + DC_BG_LEASE_MS;
    return DC_BG_OK;
}

static DcBgResult restoreInternal(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    DcBgResult result = owned(controller, session);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state == DC_BG_BLOCKED) {
        return DC_BG_BUSY;
    }
    result = advanceTime(controller, localMonotonicMs);
    if (result != DC_BG_OK || controller->state == DC_BG_NATIVE) {
        return result;
    }
    return restoreNow(controller);
}

static DcBgResult tickInternal(DcBgController *controller, uint64_t localMonotonicMs) {
    DcBgResult result = mainThread(controller);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state == DC_BG_BLOCKED) {
        return DC_BG_BUSY;
    }
    result = advanceTime(controller, localMonotonicMs);
    if (result != DC_BG_OK || controller->state == DC_BG_NATIVE) {
        return result;
    }
    result = live(controller, localMonotonicMs);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state == DC_BG_ACTIVE && !freshOutput(controller, localMonotonicMs, false)) {
        return stopFor(controller, DC_BG_NO_OUTPUT);
    }
    return DC_BG_OK;
}

static DcBgResult recoverInternal(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    DcBgResult result = owned(controller, session);
    if (result != DC_BG_OK) {
        return result;
    }
    if (controller->state != DC_BG_BLOCKED) {
        return DC_BG_BUSY;
    }
    result = advanceTime(controller, localMonotonicMs);
    return result == DC_BG_OK ? restoreNow(controller) : result;
}

typedef enum {
    OP_PREPARE, OP_COMMIT, OP_RENEW, OP_RESTORE, OP_TICK, OP_RECOVER
} Operation;

static DcBgResult dispatch(DcBgController *controller, Operation operation, uint32_t pid,
                           DcBgSession session, uint64_t now, DcBgSession *prepared) {
    DcBgResult result;
    if (!usable(controller)) {
        return DC_BG_INVALID;
    }
    if (controller->inOperation) {
        return DC_BG_BUSY;
    }
    /* Also guard callback-driven reentry, not just requests between frames. */
    controller->inOperation = true;
    switch (operation) {
        case OP_PREPARE:
            result = prepareInternal(controller, pid, now, prepared);
            break;
        case OP_COMMIT:
            result = commitInternal(controller, session, now);
            break;
        case OP_RENEW:
            result = renewInternal(controller, session, now);
            break;
        case OP_RESTORE:
            result = restoreInternal(controller, session, now);
            break;
        case OP_TICK:
            result = tickInternal(controller, now);
            break;
        case OP_RECOVER:
            result = recoverInternal(controller, session, now);
            break;
        default:
            result = DC_BG_INVALID;
            break;
    }
    controller->inOperation = false;
    return result;
}

DcBgResult dc_bg_prepare(DcBgController *controller, uint32_t trustedReceiverPid,
                        uint64_t localMonotonicMs, DcBgSession *session) {
    const DcBgSession none = {0, 0, 0};
    return dispatch(controller, OP_PREPARE, trustedReceiverPid, none, localMonotonicMs, session);
}

DcBgResult dc_bg_commit(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    return dispatch(controller, OP_COMMIT, 0, session, localMonotonicMs, NULL);
}

DcBgResult dc_bg_renew(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    return dispatch(controller, OP_RENEW, 0, session, localMonotonicMs, NULL);
}

DcBgResult dc_bg_restore(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    return dispatch(controller, OP_RESTORE, 0, session, localMonotonicMs, NULL);
}

DcBgResult dc_bg_tick(DcBgController *controller, uint64_t localMonotonicMs) {
    const DcBgSession none = {0, 0, 0};
    return dispatch(controller, OP_TICK, 0, none, localMonotonicMs, NULL);
}

DcBgResult dc_bg_recover(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs) {
    return dispatch(controller, OP_RECOVER, 0, session, localMonotonicMs, NULL);
}
