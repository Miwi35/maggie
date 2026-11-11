<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\Repository\UserRepository;
use Mcp\Capability\Attribute\McpTool;

/**
 * The planner's door (MAG-787): the morning digest and the evening review are
 * laid down by the agent's code from these settings, for every account. With
 * the service token and no user named it lists every user — like
 * claim_due_proactions; a caller acting for a user only reads that user's.
 */
#[McpTool(name: 'list_daily_rendezvous', description: "Reserved for the proaction planner — never call it in a conversation. Lists each user's daily rendezvous settings: the morning digest and the evening review, on or off, and their HH:MM hour in the user's timezone.")]
class ListDailyRendezvousTool
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(): string
    {
        $user = $this->userContext->getUser();
        $users = null !== $user ? [$user] : $this->userRepository->findAll();

        $preferences = [];
        foreach ($this->preferenceRepository->findBy(['user' => $users]) as $preference) {
            $preferences[(string) $preference->getUser()->getId()] = $preference;
        }

        return json_encode([
            'users' => array_map(
                static fn (User $u): array => self::describe($u, $preferences[(string) $u->getId()] ?? null),
                $users,
            ),
        ], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private static function describe(User $user, ?UserPreference $preference): array
    {
        // A user who never opened the settings has the defaults: the entity's own.
        $preference ??= new UserPreference();

        return [
            'userId' => (string) $user->getId(),
            'email' => $user->getEmail(),
            'timezone' => $preference->getTimezone(),
            'morningDigestEnabled' => $preference->getMorningDigestEnabled(),
            'morningDigestTime' => $preference->getMorningDigestTime(),
            'eveningReviewEnabled' => $preference->getEveningReviewEnabled(),
            'eveningReviewTime' => $preference->getEveningReviewTime(),
        ];
    }
}
