package com.maggie.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Publishes the [UiTags] under this node as the resource ids UiAutomator
 * reports — the only thing a Maestro `id:` selector can match (MAG-98).
 *
 * **Once per window, not once per app.** `testTagsAsResourceId` is resolved by
 * walking a semantics node's parents *within one owner*, and a `Dialog` or a
 * `ModalBottomSheet` is a separate platform window with its own
 * `AndroidComposeView`, hence its own semantics root. A flag set on the
 * activity's content does not reach them — which is how the first CI run of this
 * harness failed: `chat_open`, in the activity's tree, resolved; `chat_input`,
 * inside the chat sheet's `Dialog`, had no resource id at all.
 *
 * So every composable that opens a window **and** carries a tag applies this to
 * the root of its content. `task e2e:mobile:lint` checks that, because nothing
 * else can: the lint passed cleanly on the very ids the emulator could not find.
 *
 * A modifier rather than a wrapping composable, so it adds no layout node: it
 * goes on a node the screen already has.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.uiTagRoot(): Modifier = semantics { testTagsAsResourceId = true }
