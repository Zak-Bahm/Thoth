package com.bahm.thoth.knowledge

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchService @Inject constructor(
    private val zimRepository: ZimRepository,
)
