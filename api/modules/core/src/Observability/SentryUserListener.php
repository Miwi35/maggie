<?php

declare(strict_types=1);

namespace Maggie\Core\Observability;

use Maggie\Core\Entity\User;
use Sentry\State\HubInterface;
use Sentry\State\Scope;
use Sentry\UserDataBag;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\Security\Http\Event\LoginSuccessEvent;

/**
 * Puts the authenticated user's id — and nothing else — on the Sentry scope,
 * so an error can be traced to whose request it was.
 *
 * The SDK's own login listener would send the user identifier, which is the
 * e-mail here; it stays silent because `send_default_pii` is off. The JWT
 * authenticator raises LoginSuccessEvent on every stateless request, which is
 * why this listens there rather than on kernel.request.
 */
final readonly class SentryUserListener
{
    public function __construct(private HubInterface $hub)
    {
    }

    #[AsEventListener]
    public function __invoke(LoginSuccessEvent $event): void
    {
        $user = $event->getUser();

        if (!$user instanceof User) {
            return;
        }

        $id = (string) $user->getId();
        $this->hub->configureScope(static function (Scope $scope) use ($id): void {
            $scope->setUser(UserDataBag::createFromUserIdentifier($id));
        });
    }
}
