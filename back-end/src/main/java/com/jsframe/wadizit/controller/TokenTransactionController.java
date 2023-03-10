package com.jsframe.wadizit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsframe.wadizit.dto.*;
import com.jsframe.wadizit.entity.*;
import com.jsframe.wadizit.repository.*;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpSession;
import java.util.*;

@Log
@RestController
public class TokenTransactionController {
    @Autowired
    private MemberRepository memberRepo;
    @Autowired
    private TokenRepository tokenRepo;
    @Autowired
    private FundingRepository fundingRepo;
    @Autowired
    private TokenOrderRepository tokenOrderRepo;
    @Autowired
    private TokenPossessionRepository tokenPossessionRepo;
    @Autowired
    private TokenTransactionRepository tokenTransactionRepo;
    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    private static final int ORDER_TYPE_BUY = 1;
    private static final int ORDER_TYPE_SELL = 2;

    private static final int ORDER_STATUS_RD = 1;
    private static final int ORDER_STATUS_TX = 2;
    private static final int ORDER_STATUS_CC = 3;

    private ObjectMapper objectMapper = new ObjectMapper();
    private Map<Long, Token> tokenMap = new HashMap<>();
    private Map<Long, List<TokenOrder>> tokenOrderMap = new HashMap<>();
    private Map<Long, List<TokenTransaction>> tokenTransactionMap = new HashMap<>();

    private TokenInitRes retInitError(int code, String msg) {
        TokenInitRes ret = new TokenInitRes();
        ret.setRetCode(code);
        ret.setErrorMsg(msg);
        return ret;
    }

    private TokenOrderRes retOrderError(int code, String msg) {
        TokenOrderRes ret = new TokenOrderRes();
        ret.setRetCode(code);
        ret.setErrorMsg(msg);
        return ret;
    }

    @PostConstruct
    private void init() {
        List<Token> tokenList = tokenRepo.findAll();
        for (int i=0; i<tokenList.size(); i++) {
//            Token token = tokenList.get(i);
//            tokenMap.put(token.getTokenNum(), token);
//
//            // ?????? ?????? ????????? ??????
//            List<TokenOrder> tokenOrderList = tokenOrderRepo.findAllByTokenNumOrderByCreateDate(token);
//            if (tokenOrderList == null) tokenOrderList = new ArrayList<>();
//            tokenOrderMap.put(token.getTokenNum(), tokenOrderList);
//
//            // ?????? ?????? ????????? ??????
//            List<TokenTransaction> tokenTransactionList = tokenTransactionRepo.findAllByTokenNumOrderByCreateDate(token);
//            if (tokenTransactionList == null) tokenTransactionList = new ArrayList<>();
//            tokenTransactionMap.put(token.getTokenNum(), tokenTransactionList);
        }
    }

    @MessageMapping("/init/{memberNum}")
    public void init(@DestinationVariable long memberNum, TokenInitReq tir, SimpMessageHeaderAccessor mha) {
        String destURI = "/queue/init-" + memberNum;
        HttpSession session = (HttpSession) mha.getSessionAttributes().get("httpSession");
        Member member = (Member) session.getAttribute("mem");
        if (member == null) {
            simpMessagingTemplate.convertAndSend(destURI, retInitError(403, "Login required"));
            return;
        }
        // ?????? ?????? ?????? ??????
        member = memberRepo.findById(member.getMemberNum()).get();

        // ????????? ????????? ????????? ?????? ??????
        TokenInitRes initRes = new TokenInitRes();

        // ?????? ?????? ??????
        Optional<Token> tokenOpt = tokenRepo.findById(tir.getTokenNum());
        if (tokenOpt.isPresent() == false) {
            simpMessagingTemplate.convertAndSend(destURI, retInitError(500, "Invalid token number"));
            return;
        }
        Token token = tokenOpt.get();

        Funding funding = fundingRepo.findById(token.getTokenNum()).get();
        initRes.setEndDate(funding.getEndDate());
        initRes.setFundingStatus(funding.getStatus());

        // ???????????? ?????? ????????? ??????
        List<TokenOrder> tokenOrderList =
                tokenOrderRepo.findAllByStatusEqualsAndTokenNumOrderByCreateDate(ORDER_STATUS_RD, token);
        // ?????? ????????? ??????
        // List<TokenTransaction> tokenTransactionList = tokenTransactionRepo.findAllByTokenNumOrderByCreateDate(token);

        // ??????, ??????, ?????? ?????? ??????
        initRes.setToken(new TokenSimple(token));
        initRes.setTokenOrderList(TokenOrderSimple.convertSimple(tokenOrderList));
        // initRes.setTokenTransactionList(TokenTransactionSimple.convertSimple(tokenTransactionList));

        if (member != null) {
            initRes.setMember(member);
            // ?????? ????????? ?????? ?????? ??????
            List<TokenOrder> myTokenOrderList = tokenOrderRepo.findAllByStatusEqualsAndMemberNum(ORDER_STATUS_RD, member);
            initRes.setMyOrderList(TokenOrderSimple.convertSimple(myTokenOrderList));
            // ?????? ?????? ?????? ??????
            MemberTokenID mtID = new MemberTokenID();
            mtID.setTokenNum(token.getTokenNum());
            mtID.setMemberNum(member.getMemberNum());
            // ?????? ?????? ????????? ???????????? ?????? ??????
            if (tokenPossessionRepo.findById(mtID).isPresent() == false) {
                TokenPossession tp = new TokenPossession();
                tp.setMemberNum(member.getMemberNum());
                tp.setTokenNum(token.getTokenNum());
                tp.setAmount(0);
                tokenPossessionRepo.save(tp);
                initRes.setAvailableToken(0);
            }
            // ?????? ????????? ????????? ??????
            else {
                initRes.setAvailableToken(tokenPossessionRepo.findById(mtID).get().getAmount());
            }
        }
        simpMessagingTemplate.convertAndSend(destURI, initRes);
    }

    @MessageMapping("/order/{tokenNum}")
    @SendTo("/topic/order/{tokenNum}")
    public TokenOrderRes order(@DestinationVariable long tokenNum,
                             TokenOrder order, SimpMessageHeaderAccessor mha){
        HttpSession session = (HttpSession) mha.getSessionAttributes().get("httpSession");
        TokenOrderRes tor = new TokenOrderRes();
        Member orderer = (Member)session.getAttribute("mem");
        if (orderer == null) {
            return retOrderError(403, "Login required");
        }
        orderer = memberRepo.findById(orderer.getMemberNum()).get();
        // ?????? ?????? ??????
        if (tokenRepo.findById(tokenNum).isPresent() == false) {
            return retOrderError(500, "Invalid token number");
        }
        Token token = tokenRepo.findById(tokenNum).get();
        // ????????? ???????????? ??????
        if (order.getPrice() < 1 || order.getAmount() < 1 ||
                // ????????? ??????
                order.getPrice() % token.getParValue() != 0) {
            return retOrderError(500, "Invalid price or amount");
        }
        // ?????? ?????? ??????
        if (order.getType() != ORDER_TYPE_BUY && order.getType() != ORDER_TYPE_SELL) {
            return retOrderError(500, "Invalid order type");
        }

        // ?????? ?????? ?????? ??????
        TokenPossession tp = tokenPossessionRepo.findByMemberNumAndTokenNum(
                orderer.getMemberNum(), token.getTokenNum());
        // ?????? ?????? ??????
        if (order.getType() == ORDER_TYPE_BUY && order.getPrice() * order.getAmount() > orderer.getPoint()) {
            return retOrderError(500, "Not enough point");
        } else if (order.getType() == ORDER_TYPE_SELL && tp.getAmount() < order.getAmount()) {
            return retOrderError(500, "Not enough token");
        }

        // ?????? ?????? ????????? ??????
        order.setMemberNum(orderer);
        order.setTokenNum(token);
        order.setStatus(ORDER_STATUS_RD);
        order.setRemainAmount(order.getAmount());
        order = tokenOrderRepo.save(order);

        // ?????? ????????? ????????????(?????? -> ??????, ?????? -> ??????) ?????? ?????? ????????? ??????
        List<TokenOrder> orderPairList = order.getType() == ORDER_TYPE_BUY
                ? tokenOrderRepo.findAllByTypeEqualsAndStatusEqualsAndPriceLessThanEqualOrderByPrice(
                        ORDER_TYPE_SELL, ORDER_STATUS_RD, order.getPrice())
                : tokenOrderRepo.findAllByTypeEqualsAndStatusEqualsAndPriceGreaterThanEqualOrderByPriceDesc(
                ORDER_TYPE_BUY, ORDER_STATUS_RD, order.getPrice());

        // ?????? ??????
        for (int i=0; i<orderPairList.size(); i++) {
            TokenOrder pairOrder = orderPairList.get(i);
            long transAmount;
            if (order.getRemainAmount() <= pairOrder.getRemainAmount()) {
                transAmount = order.getRemainAmount();
                pairOrder.setRemainAmount(pairOrder.getRemainAmount() - order.getRemainAmount());
                order.setRemainAmount(0);
                order.setStatus(ORDER_STATUS_TX);
            }
            else {
                transAmount = pairOrder.getRemainAmount();
                order.setRemainAmount(order.getRemainAmount() - pairOrder.getRemainAmount());
                pairOrder.setRemainAmount(0);
                pairOrder.setStatus(ORDER_STATUS_TX);
            }
            TokenOrder buyOrder = pairOrder.getType() == ORDER_TYPE_BUY ? pairOrder : order;
            TokenOrder sellOrder = pairOrder.getType() == ORDER_TYPE_SELL ? pairOrder : order;

            // ???????????? ????????? ?????? ??? ?????? ?????? ??????
            Member seller = sellOrder.getMemberNum();
            seller.setPoint(seller.getPoint() + (int)(pairOrder.getPrice() * transAmount));
            memberRepo.save(seller);
            TokenPossession stp = tokenPossessionRepo.findByMemberNumAndTokenNum(
                    seller.getMemberNum(), token.getTokenNum());
            stp.setAmount(stp.getAmount() - transAmount);
            tokenPossessionRepo.save(stp);
            TokenTransactionSimple txs = new TokenTransactionSimple();
            txs.setSellOrder(new TokenOrderSimple(sellOrder));
            txs.setSellerTokenAmount(stp.getAmount());
            txs.setSellerMemberNum(seller.getMemberNum());
            txs.setSellerPoint(seller.getPoint());

            // ???????????? ????????? ?????? ??? ?????? ?????? ??????
            Member buyer = buyOrder.getMemberNum();
            buyer.setPoint(buyer.getPoint() - (int)(pairOrder.getPrice() * transAmount));
            memberRepo.save(buyer);
            TokenPossession btp = tokenPossessionRepo.findByMemberNumAndTokenNum(
                    buyer.getMemberNum(), token.getTokenNum());
            btp.setAmount(btp.getAmount() + transAmount);
            tokenPossessionRepo.save(btp);
            txs.setBuyOrder(new TokenOrderSimple(buyOrder));
            txs.setBuyerTokenAmount(btp.getAmount());
            txs.setBuyerMemberNum(buyer.getMemberNum());
            txs.setBuyerPoint(buyer.getPoint());

            // ?????? ?????? ????????????
            tokenOrderRepo.save(order);
            tokenOrderRepo.save(pairOrder);

            // ?????? ??????
            TokenTransaction tt = new TokenTransaction();
            tt.setBuyTokenOrderNum(buyOrder);
            tt.setSellTokenOrderNum(sellOrder);
            tt.setTokenNum(token);
            tt.setPrice(transAmount);
            tokenTransactionRepo.save(tt);

            tor.addTransaction(txs);

            // ?????? ????????? ?????? ????????? ????????? ??????
            if (order.getRemainAmount() == 0) break;
        }
        // ????????? ????????? ?????? ?????? ??????
        if (order.getRemainAmount() == order.getAmount()) {
            TokenTransactionSimple tt = new TokenTransactionSimple();
            // ???????????? ????????? ??????
            if (order.getType() == ORDER_TYPE_BUY) {
                orderer.setPoint(orderer.getPoint() - (int)(order.getPrice() * order.getAmount()));
                memberRepo.save(orderer);
                tt.setBuyOrder(new TokenOrderSimple(order));
                tt.setBuyerTokenAmount(tp.getAmount());
                tt.setBuyerPoint(orderer.getPoint());
                tt.setBuyerMemberNum(orderer.getMemberNum());
            }
            // ???????????? ?????? ?????? ??????
            else if (order.getType() == ORDER_TYPE_SELL) {
                tp.setAmount(tp.getAmount() - order.getAmount());
                tokenPossessionRepo.save(tp);
                tt.setSellOrder(new TokenOrderSimple(order));
                tt.setSellerMemberNum(orderer.getMemberNum());
                tt.setSellerPoint(orderer.getPoint());
                tt.setSellerTokenAmount(tp.getAmount());
            }
            tor.addTransaction(tt);
        }
        return tor;
    }

    @MessageMapping("/cancel/{tokenNum}")
    @SendTo("/topic/cancel/{tokenNum}")
    public TokenOrderRes cancel(@DestinationVariable long tokenNum,
                                TokenOrder cancelOrder, SimpMessageHeaderAccessor mha){
        HttpSession session = (HttpSession) mha.getSessionAttributes().get("httpSession");
        Member canceler = (Member)session.getAttribute("mem");
        if (canceler == null) {
            return retOrderError(403, "Login required");
        }
        canceler = memberRepo.findById(canceler.getMemberNum()).get();
        // ?????? ?????? ??????
        Optional<Token> tokenOpt = tokenRepo.findById(tokenNum);
        if (tokenOpt.isPresent() == false) {
            return retOrderError(500, "Invalid token number");
        }
        Token token = tokenOpt.get();
        // ?????? ?????? ??????
        Optional<TokenOrder> orderOpt = tokenOrderRepo.findById(cancelOrder.getTokenOrderNum());
        if (orderOpt.isPresent() == false) {
            return retOrderError(500, "Invalid order number");
        }
        TokenOrder order = orderOpt.get();

        // ????????? ????????? ???????????? ??????????????? ??????
        if (canceler.getMemberNum() != order.getMemberNum().getMemberNum()) {
            return retOrderError(500, "Orderer and login account do not match");
        }
        // ?????? ?????? ?????? ??????
        TokenPossession tp = tokenPossessionRepo.findByMemberNumAndTokenNum(
                canceler.getMemberNum(), token.getTokenNum());
        // ?????? ?????? ????????? ?????? ???????????? ?????? ??????
        switch ((int)order.getType()) {
            // ?????? ?????? ????????? ??????: ?????? ?????? ??????
            case ORDER_TYPE_BUY:
                canceler.setPoint((int)(canceler.getPoint() + order.getPrice() * order.getRemainAmount()));
                memberRepo.save(canceler);
                break;
            // ?????? ?????? ????????? ??????: ?????? ?????? ??????
            case ORDER_TYPE_SELL:
                tp.setAmount(tp.getAmount() + order.getRemainAmount());
                tokenPossessionRepo.save(tp);
                break;
            default:
                return retOrderError(500, "Invalid previous order number");
        }
        // ?????? ?????? ??????
        order.setStatus(ORDER_STATUS_CC);
        tokenOrderRepo.save(order);

        TokenOrderRes tor = new TokenOrderRes();
        TokenTransactionSimple tori = new TokenTransactionSimple();
        tori.setBuyerMemberNum(canceler.getMemberNum());
        tori.setCancelOrder(new TokenOrderSimple(order));
        tori.setBuyerPoint(canceler.getPoint());
        tori.setBuyerTokenAmount(tp.getAmount());
        tor.addTransaction(tori);

        return tor;
    }
}