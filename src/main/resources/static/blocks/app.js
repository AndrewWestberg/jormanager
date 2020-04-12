$.urlParam = function(name) {
    var results = new RegExp('[\?&]' + name + '=([^&#]*)')
        .exec(window.location.search);

    return (results !== null) ? results[1] || 0 : false;
}

$(document).ready(function() {
    var pool = $.urlParam('pool');
    if (pool == false) {
        pool = "0";
    }
    $.getJSON("/api/blocks/" + pool, function(data) {
        console.log(data);
        data.sort(function(a, b) {
            const epochA = parseInt(a.scheduled_at_date);
            const epochB = parseInt(b.scheduled_at_date);
            var slotA = parseInt(a.scheduled_at_date.split(".")[1]);
            var slotB = parseInt(b.scheduled_at_date.split(".")[1]);
            return ((epochA > epochB) ? -1 : ((epochA < epochB) ? 1 : ((slotA > slotB) ? -1 : ((slotA < slotB) ? 1 : 0))));
        });
        var epochs = new Set();
        $.each(data, function(index, block) {
            epochs.add(parseInt(block.scheduled_at_date));
        });
        var epoch = $.urlParam('epoch');
        if (epoch == false && data.length > 0) {
            epoch = parseInt(data[0].scheduled_at_date)
        }
        epochs.forEach(function(ep){
            $(".epoch_select").append(
                "<option value='"+ep+"' "+(ep==epoch?"selected":"")+">Epoch "+ep+"</option>"
            );
        });

        data = data.filter(function(block) {
            return parseInt(block.scheduled_at_date) == epoch
        });
        var tbody = $(".blocks_tbody");
        var blockNumber = 1;
        for (let i = data.length - 1; i >= 0; i--) {
            if (i < data.length - 1 && parseInt(data[i + 1].scheduled_at_date) != parseInt(data[i].scheduled_at_date)) {
                // Different Epoch, reset count to 1
                blockNumber = 1;
            }
            data[i].blockNumber = blockNumber;
            blockNumber++;
        }
        var pendingCount = 0;
        var mintedCount = 0;
        var snipedCount = 0;
        var missedCount = 0;
        const now = new Date();
        $.each(data, function(index, block) {
            const scheduledDate = new Date(block.scheduled_at_time);
            const time = $.format.date(scheduledDate, "yyyy-MM-dd h:mm:ss p");
            const status = (block.finished_at_time == null ? (scheduledDate > now ? block.status : "---") : ((block.hasOwnProperty("minted") && block.minted != null) ? (block.minted?"MINTED!":"Sniped") : (block.status.hasOwnProperty("Rejected") ? "Rejected" : "Completed")));
            const hash = block && block.status && block.status.Block && block.status.Block.block
            const shortHash = hash != null ? (hash.substring(0, 5) + "...") : "---"
            tbody.append(
                "<tr>" +
                "<td>" + block.blockNumber + "</td>" +
                "<td>" + block.scheduled_at_date + "</td>" +
                "<td>" + time + "</td>" +
                "<td class='block_status'>" + (status == "Sniped" ? ("<a href='https://explorer.incentivized-testnet.iohkdev.io/block/chainLength/"+block.status.Block.chain_length+"' target='sniped_"+block.status.Block.chain_length+"'>Sniped</a>") : status) + "</td>" +
                "<td>" + (shortHash == "---" ? shortHash : "<a href='https://explorer.incentivized-testnet.iohkdev.io/block/" + hash + "' target='block_" + hash + "'>" + shortHash + "</a>") + "</td>" +
                "</tr>");

            switch(status) {
                case "Pending":
                case "Completed":
                    pendingCount++;
                    break;
                case "MINTED!":
                    mintedCount++;
                    break;
                case "Sniped":
                    snipedCount++;
                    break;
                case "Rejected":
                default:
                    missedCount++;
                    break;
            }
        });
        $(".pending").html(pendingCount);
        $(".minted").html(mintedCount);
        $(".sniped").html(snipedCount);
        $(".missed").html(missedCount);
    });
});