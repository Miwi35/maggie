<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\FundingSourceKind;
use Symfony\Component\Uid\Ulid;

/** @extends ServiceEntityRepository<Project> */
class ProjectRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Project::class);
    }

    /** @return list<Project> in priority order, 0 first */
    public function findByUser(User|string $user): array
    {
        return $this->findBy(['user' => $user], ['position' => 'ASC']);
    }

    /** The user's project, or null — also for an id that is not a ULID. */
    public function findOneOfUser(User|string $user, string $projectId): ?Project
    {
        if (!Ulid::isValid($projectId)) {
            return null;
        }

        return $this->findOneBy(['id' => $projectId, 'user' => $user]);
    }

    /** The position a new project takes: after the last one. */
    public function nextPosition(User $user): int
    {
        $max = $this->createQueryBuilder('p')
            ->select('MAX(p.position)')
            ->where('p.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getSingleScalarResult();

        return null === $max ? 0 : (int) $max + 1;
    }

    /** @return list<Project> the projects that wait for this one */
    public function findDependents(Project $project): array
    {
        return array_values($this->createQueryBuilder('p')
            ->innerJoin('p.dependsOn', 'd')
            ->where('d.id = :id')
            ->setParameter('id', $project->getId(), 'ulid')
            ->getQuery()
            ->getResult());
    }

    /** @return list<ProjectFundingSource> what this project's resale poses on the projects it funds (MAG-557) */
    public function findResaleSourcesOf(Project $source): array
    {
        return $this->getEntityManager()->getRepository(ProjectFundingSource::class)->findBy([
            'sourceProject' => $source,
            'kind' => FundingSourceKind::Resale,
        ]);
    }

    /** @return list<ProjectInvestment> the investments whose resale is set aside for this project */
    public function findInvestmentsTargeting(Project $target): array
    {
        return $this->getEntityManager()->getRepository(ProjectInvestment::class)->findBy(['resaleTarget' => $target]);
    }
}
