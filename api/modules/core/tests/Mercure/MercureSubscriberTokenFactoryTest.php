<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mercure;

use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use PHPUnit\Framework\TestCase;

final class MercureSubscriberTokenFactoryTest extends TestCase
{
    public function testClaimsCoverTheUsersMultiSegmentTopicsAndNobodyElses(): void
    {
        $user = new User();
        $id = (string) $user->getId();

        $token = (new MercureSubscriberTokenFactory('a-mercure-secret'))->createForUser($user);
        $subscribe = $this->claims($token)['mercure']['subscribe'];

        // {topic} (simple expansion) stops at "/" and would match nothing
        // under /users/<id>/api/tasks/<id>; {+topic} (reserved expansion)
        // crosses segments. Private updates depend on it.
        self::assertContains('/users/' . $id . '/{+topic}', $subscribe);
        self::assertNotContains('/users/' . $id . '/{topic}', $subscribe);

        // The agent publishes outside /users/…, on topics keyed by the user id.
        foreach (['chat', 'contexts', 'proactions', 'instructions', 'skills'] as $name) {
            self::assertContains('/' . $name . '/' . $id, $subscribe);
        }

        foreach ($subscribe as $selector) {
            self::assertStringContainsString($id, $selector, 'Every selector must be scoped to the token owner.');
        }
    }

    /** @return array<string, mixed> */
    private function claims(string $jwt): array
    {
        return json_decode(
            (string) base64_decode(strtr(explode('.', $jwt)[1], '-_', '+/'), true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }
}
