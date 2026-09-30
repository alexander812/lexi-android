(function () {
    var androidBridge = window.AndroidBridge;
    var available = typeof androidBridge !== "undefined" && androidBridge !== null;
    var pending = new Map();
    var sequence = 0;

    function nextId() {
        sequence += 1;
        return "req-" + Date.now() + "-" + sequence;
    }

    window.__nativeBridgeResolve = function (requestId, payloadJson) {
        var entry = pending.get(requestId);
        if (!entry) return;
        pending.delete(requestId);
        var payload;
        try {
            payload = JSON.parse(payloadJson);
        } catch (error) {
            payload = { ok: false, error: "invalid_payload" };
        }
        if (payload.ok) {
            entry.resolve(payload.data === undefined ? null : payload.data);
        } else {
            entry.reject(new Error(payload.error || "native_error"));
        }
    };

    function callNative(method, params) {
        if (!available) return Promise.reject(new Error("bridge_unavailable"));
        return new Promise(function (resolve, reject) {
            var requestId = nextId();
            pending.set(requestId, { resolve: resolve, reject: reject });
            androidBridge.call(requestId, method, JSON.stringify(params || {}));
        });
    }

    window.nativeBridge = {
        available: available,
        platform: available ? "android" : "web",
        call: callNative,
        vibrate: function (durationMs) {
            var duration = durationMs || 50;
            if (available) return callNative("vibrate", { durationMs: duration });
            if (navigator.vibrate) {
                navigator.vibrate(duration);
                return Promise.resolve({ fallback: true, vibratedMs: duration });
            }
            return Promise.reject(new Error("vibration_unavailable"));
        },
        deviceInfo: function () {
            if (available) return callNative("deviceInfo", {});
            return Promise.resolve({ platform: "web", userAgent: navigator.userAgent });
        }
    };
})();
