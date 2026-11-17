package com.example.campusmarkettek.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.campusmarkettek.data.remote.HealthApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HealthUiState {
    data object Loading : HealthUiState
    data class Connected(val database: String) : HealthUiState
    data class Failed(val reason: String) : HealthUiState
}

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val api: HealthApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HealthUiState>(HealthUiState.Loading)
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        check()
    }

    fun check() {
        _uiState.value = HealthUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                HealthUiState.Connected(database = api.health().database)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HealthUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
