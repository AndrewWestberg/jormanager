<template>
    <div class="body-container">
        <b-container>
            <b-table striped :items="blocks"></b-table>
        </b-container>
    </div>
</template>

<script>
    export default {
        name: "Body",
        props: {
            stompClient: Object
        },
        data() {
            return {
                blocks: []
            }
        },
        methods: {
            send() {
                if(this.stompClient && this.stompClient.connected) {
                    console.log("Request blocks");
                    this.stompClient.send("/jormanager/blocks")
                }
            }
        },
        mounted() {
            this.blocks = []
            this.stompClient.subscribe("/topic/blocks", tick => {
                // console.log(tick);
                let socketResult = JSON.parse(tick.body)
                if(socketResult.data) {
                    this.blocks.unshift(socketResult.data)
                } else {
                    // show notification error message
                }
            })

            this.send()
        }
    }
</script>

<style scoped>

</style>