<?php

namespace Maggie\Core\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class UserApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed> $payload */
    private function patchUser(User $user, array $payload): void
    {
        $this->client->request('PATCH', '/api/users/' . $user->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(User $user): User
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(User::class)->find($user->getId());
    }

    private function loadUser(): User
    {
        $this->loadFixtures('UserApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('test_user');
    }

    public function testPatchUserRequiresAuthentication(): void
    {
        $user = $this->loadUser();

        $this->client->request('PATCH', '/api/users/' . $user->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['avatar' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullAvatarClearsIt(): void
    {
        $user = $this->loadUser();

        $this->patchUser($user, ['avatar' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($user);
        self::assertNull($reloaded->getAvatar());
        self::assertSame('Update User Test', $reloaded->getName(), 'Fields left out of the payload are untouched');
        $this->assertMercureUpdatePublished('/api/users/');
        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testPatchWithoutAvatarKeepsIt(): void
    {
        $user = $this->loadUser();

        $this->patchUser($user, ['name' => 'Renamed']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($user);
        self::assertSame('Renamed', $reloaded->getName());
        self::assertSame('https://example.com/avatar.png', $reloaded->getAvatar());
    }
}
