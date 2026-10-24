<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

use Doctrine\ORM\EntityManagerInterface;
use Fidry\AliceDataFixtures\LoaderInterface;
use Fidry\AliceDataFixtures\Persistence\PurgeMode;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Maggie\Core\E2e\Fixture\E2eFixtureCatalog;
use Maggie\Core\E2e\Fixture\FixtureEffects;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Uid\Ulid;

/**
 * Creates the account one journey owns, so no journey reads what another left.
 *
 * The data comes from the seed's own fixture files, sliced to this user
 * (FixtureSlice): the seed's account becomes the new one, and its neighbour —
 * the other account the isolation checks look for — comes with it, so a
 * journey finds the same world the shared seed gave it, without anyone else in it.
 */
final class E2eUserFactory
{
    private const SEED_USER = 'e2e_user';
    private const SEED_STOCK_USER = 'e2e_stock_user';
    private const SEED_NEIGHBOUR = 'e2e_other_user';
    private const SEED_MANIFEST = 'var/e2e/seed-manifest.json';

    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly E2eDateProvider $dateProvider,
        #[Autowire(service: 'fidry_alice_data_fixtures.loader.doctrine')]
        private readonly LoaderInterface $fixtureLoader,
        private readonly FixtureEffects $fixtureEffects,
        private readonly E2eFixtureCatalog $catalog,
        private readonly LoadedObjectsIndexer $indexer,
        private readonly string $projectDir,
    ) {
    }

    public function create(E2eUserProfile $profile): JourneyUser
    {
        $anchor = $this->seedAnchor();
        $this->dateProvider->setAnchor($anchor);

        $id = strtolower((string) new Ulid());
        $withNeighbour = E2eUserProfile::Standard === $profile;

        $owner = E2eUserProfile::Stock === $profile ? self::SEED_STOCK_USER : self::SEED_USER;

        $slice = match ($profile) {
            E2eUserProfile::Empty => FixtureSlice::of($this->catalog->files(), [self::SEED_USER], [User::class, UserPreference::class]),
            E2eUserProfile::Standard => FixtureSlice::of($this->catalog->files(), [self::SEED_USER, self::SEED_NEIGHBOUR]),
            E2eUserProfile::Stock => FixtureSlice::of($this->catalog->files(), [self::SEED_STOCK_USER]),
        };

        $slice = $this->withIdentity($slice, $owner, $id);
        if ($withNeighbour) {
            $slice = $this->withIdentity($slice, self::SEED_NEIGHBOUR, $id.'-neighbour');
        }

        $objects = $this->load($slice);
        $this->indexer->index($objects);
        $this->entityManager->clear();

        $user = $objects[$owner];
        \assert($user instanceof User);
        $neighbour = $withNeighbour ? $objects[self::SEED_NEIGHBOUR] : null;
        \assert(null === $neighbour || $neighbour instanceof User);

        return new JourneyUser($user, $neighbour, $this->catalog->referencesOf($objects), $anchor);
    }

    /**
     * @param array<class-string, array<string, mixed>> $slice
     *
     * @return array<class-string, array<string, mixed>>
     */
    private function withIdentity(array $slice, string $reference, string $id): array
    {
        $userClass = User::class;
        $slice[$userClass][$reference]['email'] = sprintf('journey-%s@e2e.test', $id);
        $slice[$userClass][$reference]['googleId'] = 'journey-'.$id;

        return $slice;
    }

    /**
     * @param array<class-string, array<string, mixed>> $slice
     *
     * @return array<string, object>
     */
    private function load(array $slice): array
    {
        $file = sys_get_temp_dir().'/e2e-journey-user-'.strtolower((string) new Ulid()).'.yaml';
        file_put_contents($file, FixtureSlice::dump($slice));

        try {
            // History, like the seed's: nothing a journey starts from is a movement to record.
            return $this->fixtureEffects->withoutEffects(fn () => $this->fixtureLoader->load(
                [$file],
                [],
                [],
                PurgeMode::createNoPurgeMode(),
            ));
        } finally {
            unlink($file);
        }
    }

    /** The day the seed anchored on, so a journey user's dates agree with the seeded world's. */
    private function seedAnchor(): \DateTimeImmutable
    {
        $manifest = $this->projectDir.'/'.self::SEED_MANIFEST;
        $contents = is_readable($manifest) ? file_get_contents($manifest) : false;

        if (false !== $contents) {
            $anchor = json_decode($contents, true)['anchor'] ?? null;
            if (\is_string($anchor)) {
                return new \DateTimeImmutable($anchor);
            }
        }

        return E2eDateProvider::anchorFor(new \DateTimeImmutable());
    }
}
