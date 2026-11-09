package com.streamvault.feature.settings.presentation

import android.content.Context
import org.mockito.Answers
import org.mockito.kotlin.mock
import org.mockito.stubbing.Answer

/** What [fakeText] and [fakeTextContext] return for [resId] and its format [args]; tests compare against this. */
internal fun resText(resId: Int, vararg args: Any): String =
    "res:$resId" + if (args.isEmpty()) "" else args.joinToString(prefix = "[", postfix = "]", separator = "|")

/** A `text` resolver for actions that take one: it renders the resource id, so tests assert the id is shown. */
internal val fakeText: (Int, List<Any>) -> String = { resId, args -> resText(resId, *args.toTypedArray()) }

/** A [Context] whose `getString` renders the resource id like [resText], for actions that read resources directly. */
internal fun fakeTextContext(): Context = mock<Context>(defaultAnswer = Answer { call ->
    if (call.method.name == "getString") {
        val resId = call.arguments[0] as Int
        resText(resId, *call.arguments.drop(1).toTypedArray())
    } else {
        Answers.RETURNS_DEFAULTS.answer(call)
    }
})
