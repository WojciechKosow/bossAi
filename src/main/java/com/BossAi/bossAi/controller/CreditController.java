package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.dto.CreditBalanceDTO;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserPlan;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.PlanSelectionService;
import com.BossAi.bossAi.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class CreditController {

    private final UserRepository userRepository;
    private final PlanSelectionService planSelectionService;
    private final WalletService walletService;

    /** The user's spendable credits: highest active plan's remaining + wallet. */
    @GetMapping("/credits")
    public CreditBalanceDTO credits(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));

        int planCredits = 0;
        try {
            // The plan generation draws from first — the one whose credits are
            // actually spendable now (not lower plans whose credits are stranded).
            UserPlan active = planSelectionService.selectActivePlan(user);
            planCredits = Math.max(0, active.getCreditsTotal() - active.getCreditsUsed());
        } catch (ResponseStatusException ignored) {
            // No active plan at all — planCredits stays 0.
        }

        int walletCredits = walletService.getBalance(user.getId());
        return new CreditBalanceDTO(planCredits, walletCredits, planCredits + walletCredits);
    }
}
