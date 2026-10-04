<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mercure;

use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureAccessToken;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use PHPUnit\Framework\TestCase;

final class MercureSubscriberTokenFactoryTest extends TestCase
{
    private const SECRET = 'a-mercure-secret-of-at-least-32-bytes';

    public function testGrantsCoverTheUsersMultiSegmentTopicsAndNobodyElses(): void
    {
        $user = new User();
        $id = (string) $user->getId();

        $claims = MercureAccessTokenTest::decode($this->factory()->createForUser($user))['claims'];
        $topics = $claims['authorization_details'][0]['topics'];

        // `*` in a URL Pattern crosses "/", so one grant covers
        // /users/<id>/api/tasks/<id>. Private updates depend on it.
        self::assertContains(
            ['match' => '/users/'.$id.'/*', 'match_type' => 'urlpattern'],
            $topics,
        );

        // The agent publishes outside /users/…, on topics keyed by the user id.
        foreach (['chat', 'contexts', 'proactions', 'instructions', 'skills', 'memory'] as $name) {
            self::assertContains(['match' => '/'.$name.'/'.$id], $topics);
        }

        foreach ($topics as $topic) {
            self::assertStringContainsString($id, $topic['match'], 'Every grant must be scoped to the token owner.');
        }
    }

    public function testTheTokenOnlyAllowsSubscribing(): void
    {
        $claims = MercureAccessTokenTest::decode($this->factory()->createForUser(new User()))['claims'];

        self::assertCount(1, $claims['authorization_details']);
        self::assertSame(['subscribe'], $claims['authorization_details'][0]['actions']);
    }

    public function testTheCookieCarriesTheTokenOnTheHubPathOnly(): void
    {
        $cookie = $this->factory()->createCookieForUser(new User());

        self::assertSame('mercureAuthorization', $cookie->getName());
        self::assertSame('/.well-known/mercure', $cookie->getPath());
        self::assertTrue($cookie->isHttpOnly());
        self::assertTrue($cookie->isSecure());
    }

    private function factory(): MercureSubscriberTokenFactory
    {
        return new MercureSubscriberTokenFactory(
            new MercureAccessToken(self::SECRET, 'https://maggie.test/.well-known/mercure'),
        );
    }
}
