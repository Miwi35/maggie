package com.maggie.app.data.car

/** A phone that is not in a car: nothing is said there, nothing is scheduled. */
fun noCar() = CarMessages(
    isConnected = { false },
    post = { _, _ -> },
    isShowing = { false },
    scheduleNudge = { _, _ -> },
    cancelNudge = {},
)
