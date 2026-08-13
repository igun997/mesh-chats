package com.meshchats.app.ui.mesh

import androidx.lifecycle.ViewModel
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.TransportId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val repository: MeshStateRepository,
) : ViewModel() {

    val state: StateFlow<MeshState> = repository.state

    fun toggleTransport(id: TransportId, enabled: Boolean) =
        repository.setTransportEnabled(id, enabled)

    fun setLocalMeshOnly(enabled: Boolean) = repository.setLocalMeshOnly(enabled)
}
