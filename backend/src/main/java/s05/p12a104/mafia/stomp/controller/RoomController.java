package s05.p12a104.mafia.stomp.controller;


import java.util.Map;
import java.util.Timer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import s05.p12a104.mafia.api.service.GameSessionService;
import s05.p12a104.mafia.common.util.TimeUtils;
import s05.p12a104.mafia.domain.entity.GameSession;
import s05.p12a104.mafia.domain.enums.GameRole;
import s05.p12a104.mafia.domain.enums.GameState;
import s05.p12a104.mafia.redispubsub.RedisPublisher;
import s05.p12a104.mafia.stomp.response.GameSessionStompJoinRes;
import s05.p12a104.mafia.stomp.response.GameSessionStompLeaveRes;
import s05.p12a104.mafia.stomp.response.GameStatusRes;
import s05.p12a104.mafia.stomp.response.ObserverJoinRes;
import s05.p12a104.mafia.stomp.response.PlayerRoleRes;
import s05.p12a104.mafia.stomp.response.StompRejoinPlayer;
import s05.p12a104.mafia.stomp.response.StompResForRejoiningPlayer;
import s05.p12a104.mafia.stomp.service.GameSessionVoteService;
import s05.p12a104.mafia.stomp.task.StartFinTimerTask;

@Slf4j
@RequiredArgsConstructor
@Controller
public class RoomController {

  private final GameSessionService gameSessionService;
  private final GameSessionVoteService gameSessionVoteService;
  private final SimpMessagingTemplate simpMessagingTemplate;
  private final RedisPublisher redisPublisher;
  private final ChannelTopic topicStartFin;

  @MessageMapping("/{roomId}/join")
  public void joinGameSession(@DestinationVariable String roomId) {
    log.info("req STOMP /ws/gamesession/{}/join", roomId);
    GameSessionStompJoinRes res = GameSessionStompJoinRes.of(gameSessionService.findById(roomId));

    log.info("res STOMP /ws/gamesession/{}/join - res : {}", roomId, res);
    simpMessagingTemplate.convertAndSend("/sub/" + roomId, res);
  }

  @MessageMapping("/{roomId}/leave")
  public void leaveGameSession(SimpMessageHeaderAccessor accessor,
      @DestinationVariable String roomId) {
    String playerId = accessor.getUser().getName();
    log.info("req STOMP /ws/gamesession/{}/leave - playerId : {}", roomId, playerId);

    GameSession gameSession = gameSessionService.removeUser(roomId, playerId);
    GameSessionStompLeaveRes res = new GameSessionStompLeaveRes(gameSession.getHostId(), playerId);
    log.info("res STOMP /ws/gamesession/{}/leave - res : {}", roomId, res);
    simpMessagingTemplate.convertAndSend("/sub/" + roomId, res);
  }

  @MessageMapping("/{roomId}/rejoin")
  public void rejoinGameSession(SimpMessageHeaderAccessor accessor,
      @DestinationVariable String roomId) {
    String playerId = accessor.getUser().getName();
    log.info("req STOMP /ws/gamesession/{}/rejoin - playerId : {}", roomId, playerId);
    GameSession gameSession = gameSessionService.findById(roomId);
    if (gameSession.getState() != GameState.STARTED) {
      joinGameSession(roomId);
      return;
    }

    Map<String, Boolean> confirmResult = gameSessionVoteService.getConfirm(roomId, playerId);
    log.info("Room {} rejoin confirmSize {}", gameSession.getRoomId(), confirmResult.size());
    StompResForRejoiningPlayer resForRejoningPlayer =
        StompResForRejoiningPlayer.of(gameSession, playerId, confirmResult);
    simpMessagingTemplate.convertAndSend("/sub/" + roomId + "/" + playerId, resForRejoningPlayer);

    StompRejoinPlayer resForExistingPlayer =
        StompRejoinPlayer.of(gameSession.getPlayerMap().get(playerId));
    simpMessagingTemplate.convertAndSend("/sub/" + roomId, resForExistingPlayer);

    log.info("req STOMP /ws/gamesession/{}/rejoin - resForRejoningPlayer : {}", roomId,
        resForRejoningPlayer);
    log.info("req STOMP /ws/gamesession/{}/rejoin - resForExistingPlayer : {}", roomId,
        resForExistingPlayer);
  }

  @MessageMapping("/{roomId}/start")
  public void startGame(SimpMessageHeaderAccessor accessor, @DestinationVariable String roomId) {
    // 방장이 시작했는지 확인
    GameSession gameSession = gameSessionService.findById(roomId);
    String playerId = accessor.getUser().getName();
    if (gameSession.getState() == GameState.STARTED || !playerId.equals(gameSession.getHostId())) {
      return;
    }

    // 초기 설정하기
    gameSessionService.startGame(gameSession);

    Timer timer = new Timer();
    StartFinTimerTask task = new StartFinTimerTask(redisPublisher, topicStartFin);
    task.setRoomId(roomId);
    timer.schedule(task, TimeUtils.convertToDate(gameSession.getTimer()));

    log.info("Room {} start game", gameSession.getRoomId());

    // 전체 전송
    simpMessagingTemplate.convertAndSend("/sub/" + roomId, GameStatusRes.of(gameSession));

    // 개인 전송
    gameSession.getPlayerMap().forEach((id, player) -> {
      if (player.getRole() == GameRole.MAFIA) {
        simpMessagingTemplate.convertAndSend("/sub/" + roomId + "/" + id,
            PlayerRoleRes.of(player, gameSession.getMafias()));
      } else {
        simpMessagingTemplate.convertAndSend("/sub/" + roomId + "/" + id, PlayerRoleRes.of(player));
      }

    });
  }

  @MessageMapping("/{roomId}/OBSERVER")
  public void observerJoin(SimpMessageHeaderAccessor accessor, @DestinationVariable String roomId) {
    String playerId = accessor.getUser().getName();
    log.info("req STOMP /ws/gamesession/{}/OBSERVER - playerId : {}", roomId, playerId);

    Map<String, GameRole> playerRole = gameSessionService.addObserver(roomId, playerId);
    simpMessagingTemplate.convertAndSend("/sub/" + roomId + "/" + GameRole.OBSERVER,
        ObserverJoinRes.of(playerRole));
  }

}
