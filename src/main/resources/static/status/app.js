var stompClient = null;
var jwttoken = null;

function login() {
    var username = $("#username").val();
    var password = $("#password").val();
    $("#username").val("");
    $("#password").val("");

    $.ajax({
        url : "/authenticate",
        type: "POST",
        data: JSON.stringify({ username: username, password: password }),
        contentType: "application/json; charset=utf-8",
        dataType : "json",
        success : function(data){
            jwttoken = data.jwttoken;
            connect();
        }
    });
}

function setConnected(connected) {
    if(connected) {
        $("#loginsection").hide();
        $("#reconnectsection").hide();
        $("#statussection").show();
    } else {
        $(".pooltoolmax").html("---");
        $(".updated").html("---")
        var i;
        for(i=0;i<10;i++) {
            $(".jor"+i+".row").hide();
        }
        $("#statussection").hide();
        $("#reconnectsection").show();
    }
}

function connect() {
    var socket = new SockJS('/jormanager-websocket?access_token=' + jwttoken);
    stompClient = Stomp.over(socket);
    stompClient.connect({}, function (frame) {
        setConnected(true);
        console.log('Connected: ' + frame);
        stompClient.subscribe('/topic/status', function (data) {
            var status = JSON.parse(data.body);
            showStatus(status);
        });
    },
    function (frame) {
        setConnected(false);
    });
}

function showStatus(data) {
    $(".updated").html(new Date().toLocaleString())
    if (data.hasOwnProperty("pooltool") && data.pooltool.success == true) {
        if (data.pooltool.hasOwnProperty("pooltoolmax")) {
            $(".pooltoolmax").html(data.pooltool.pooltoolmax);
        } else {
            $(".pooltoolmax").html("---");
        }
    } else {
        $(".pooltoolmax").html("---");
    }

    var i;
    for (i = 0; i < 10; i++) {
        if (data.nodes.hasOwnProperty(i)) {
            $(".jor" + i + ".row").show();
            //console.log(data.nodes[i]);
            switch (data.nodes[i].state) {
                case "Bootstrapping":
                    $(".jor" + i + ".status").removeClass("fa-question-circle fa-check-circle fa-dizzy fa-flag").addClass("fa-hourglass");
                    $(".jor" + i + ".height").html("---");
                    $(".jor" + i + ".hash").html("---");
                    $(".jor" + i + ".time").html("---");
                    $(".jor" + i + ".peers").html("---");
                    $(".jor" + i + ".uptime").html("---");
                    break;
                case "Running":
                    if (data.nodes[i].leader == true) {
                        $(".jor" + i + ".status").removeClass("fa-question-circle fa-check-circle fa-dizzy fa-hourglass").addClass("fa-flag");
                    } else {
                        $(".jor" + i + ".status").removeClass("fa-question-circle fa-flag fa-dizzy fa-hourglass").addClass("fa-check-circle");
                    }
                    $(".jor" + i + ".height").html(data.nodes[i].lastBlockHeight);
                    $(".jor" + i + ".hash").html(data.nodes[i].lastBlockHash.substring(0, 4) + "...");

                    var seconds = parseInt((new Date().getTime() - new Date(data.nodes[i].lastBlockTime).getTime()) / 1000);
                    var days = Math.floor(seconds / (3600 * 24));
                    seconds -= days * 3600 * 24;
                    var hrs = Math.floor(seconds / 3600);
                    seconds -= hrs * 3600;
                    var mnts = Math.floor(seconds / 60);
                    seconds -= mnts * 60;
                    var blocktime = days > 0 ? days + "d " : "";
                    blocktime += days > 0 || hrs > 0 ? hrs + "h " : "";
                    blocktime += days > 0 || hrs > 0 || mnts > 0 ? mnts + "m " : "";
                    blocktime += seconds + "s ago";
                    $(".jor" + i + ".time").html(blocktime);
                    $(".jor" + i + ".peers").html(data.nodes[i].numberOfPeers);
                    seconds = parseInt(data.nodes[i].uptime);
                    days = Math.floor(seconds / (3600 * 24));
                    seconds -= days * 3600 * 24;
                    hrs = Math.floor(seconds / 3600);
                    seconds -= hrs * 3600;
                    mnts = Math.floor(seconds / 60);
                    seconds -= mnts * 60;
                    var uptime = days > 0 ? days + "d " : "";
                    uptime += days > 0 || hrs > 0 ? hrs + "h " : "";
                    uptime += days > 0 || hrs > 0 || mnts > 0 ? mnts + "m " : "";
                    uptime += seconds + "s";
                    $(".jor" + i + ".uptime").html(uptime);
                    break;
                default:
                    // who knows what state
                    $(".jor" + i + ".status").removeClass("fa-dizzy fa-check-circle fa-hourglass fa-flag").addClass("fa-question-circle");
                    $(".jor" + i + ".height").html("---");
                    $(".jor" + i + ".hash").html("---");
                    $(".jor" + i + ".time").html("---");
                    $(".jor" + i + ".peers").html("---");
                    $(".jor" + i + ".uptime").html("---");
                    break;
            }
        } else {
            // node is stopped
            $(".jor" + i + ".status").removeClass("fa-question-circle fa-check-circle fa-hourglass fa-flag").addClass("fa-dizzy");
            $(".jor" + i + ".height").html("---");
            $(".jor" + i + ".hash").html("---");
            $(".jor" + i + ".time").html("---");
            $(".jor" + i + ".peers").html("---");
            $(".jor" + i + ".uptime").html("---");
        }
    }
}

function disconnect() {
    if (stompClient !== null) {
        stompClient.disconnect();
    }
    setConnected(false);
    console.log("Disconnected");
}

$(function () {
    $("#loginform").on('submit', function (e) {
        e.preventDefault();
        login();
    });

    $("#reconnect").on('click', function(){
        connect();
    });
});