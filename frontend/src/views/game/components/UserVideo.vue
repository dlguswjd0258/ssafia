<template>
  <div v-if="streamManager">
    <ov-video :stream-manager="streamManager" />
    <div>
      <p>{{ state.clientData }}</p>
    </div>
  </div>
</template>

<script>
import OvVideo from "@/views/game/components/OvVideo";
import { reactive, computed } from 'vue'

export default {
  name: "UserVideo",

  components: {
    OvVideo,
  },

  props: {
    streamManager: Object,
  },
  setup(props) {
    const state = reactive({
      clientData: undefined,
    })
    const tmp = computed(() => { return props.streamManager.stream.connection.data})
    const array = tmp.value.split('"')
    console.log(array)
    state.clientData = array[3]
    console.log(state.clientData)
    return {
      state
    }
  }

  // computed: {
  // 	clientData () {
  // 		const { clientData } = this.getConnectionData();
  // 		return clientData;
  // 	},
  // },

  // methods: {
  // 	getConnectionData () {
  // 		const { connection } = this.streamManager.stream;
  // 		return JSON.parse(connection.data);
  // 	},
  // },
};
</script>