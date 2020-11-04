package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.ActivitySummary;
import org.qortal.api.model.NodeInfo;
import org.qortal.api.model.NodeStatus;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer.SynchronizationResult;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import com.google.common.collect.Lists;

@Path("/admin")
@Tag(name = "Admin")
public class AdminResource {

	private static final int MAX_LOG_LINES = 500;

	@Context
	HttpServletRequest request;

	@GET
	@Path("/unused")
	@Parameter(in = ParameterIn.PATH, name = "assetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "otherassetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "address", description = "an account address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	@Parameter(in = ParameterIn.QUERY, name = "count", description = "Maximum number of entries to return, 0 means none", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "limit", description = "Maximum number of entries to return, 0 means unlimited", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "offset", description = "Starting entry in results, 0 is first entry", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.QUERY, name = "reverse", description = "Reverse results", schema = @Schema(type = "boolean"))
	public String globalParameters() {
		return "";
	}

	@GET
	@Path("/uptime")
	@Operation(
		summary = "Fetch running time of server",
		description = "Returns uptime in milliseconds",
		responses = {
			@ApiResponse(
				description = "uptime in milliseconds",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number"))
			)
		}
	)
	public long uptime() {
		return System.currentTimeMillis() - Controller.startTime;
	}

	@GET
	@Path("/info")
	@Operation(
		summary = "Fetch generic node info",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NodeInfo.class))
			)
		}
	)
	public NodeInfo info() {
		NodeInfo nodeInfo = new NodeInfo();

		nodeInfo.currentTimestamp = NTP.getTime();
		nodeInfo.uptime = System.currentTimeMillis() - Controller.startTime;
		nodeInfo.buildVersion = Controller.getInstance().getVersionString();
		nodeInfo.buildTimestamp = Controller.getInstance().getBuildTimestamp();
		nodeInfo.nodeId = Network.getInstance().getOurNodeId();
		nodeInfo.isTestNet = Settings.getInstance().isTestNet();

		return nodeInfo;
	}

	@GET
	@Path("/status")
	@Operation(
		summary = "Fetch node status",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NodeStatus.class))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public NodeStatus status() {
		Security.checkApiCallAllowed(request);

		NodeStatus nodeStatus = new NodeStatus();

		return nodeStatus;
	}

	@GET
	@Path("/stop")
	@Operation(
		summary = "Shutdown",
		description = "Shutdown",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public String shutdown() {
		Security.checkApiCallAllowed(request);

		new Thread(() -> {
			// Short sleep to allow HTTP response body to be emitted
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Not important
			}

			Controller.getInstance().shutdownAndExit();
		}).start();

		return "true";
	}

	@GET
	@Path("/summary")
	@Operation(
		summary = "Summary of activity since midnight, UTC",
		responses = {
			@ApiResponse(
				content = @Content(schema = @Schema(implementation = ActivitySummary.class))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public ActivitySummary summary() {
		Security.checkApiCallAllowed(request);

		ActivitySummary summary = new ActivitySummary();

		LocalDate date = LocalDate.now();
		LocalTime time = LocalTime.of(0, 0);
		ZoneOffset offset = ZoneOffset.UTC;
		long start = OffsetDateTime.of(date, time, offset).toInstant().toEpochMilli();

		try (final Repository repository = RepositoryManager.getRepository()) {
			int startHeight = repository.getBlockRepository().getHeightFromTimestamp(start);
			int endHeight = repository.getBlockRepository().getBlockchainHeight();

			summary.setBlockCount(endHeight - startHeight);

			summary.setTransactionCountByType(repository.getTransactionRepository().getTransactionSummary(startHeight + 1, endHeight));

			summary.setAssetsIssued(repository.getAssetRepository().getRecentAssetIds(start).size());

			summary.setNamesRegistered (repository.getNameRepository().getRecentNames(start).size());

			return summary;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/enginestats")
	@Operation(
		summary = "Fetch statistics snapshot for core engine",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = Controller.StatsSnapshot.class
						)
					)
				)
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public Controller.StatsSnapshot getEngineStats() {
		Security.checkApiCallAllowed(request);

		return Controller.getInstance().getStatsSnapshot();
	}

	@GET
	@Path("/mintingaccounts")
	@Operation(
		summary = "List public keys of accounts used to mint blocks by BlockMinter",
		description = "Returns PUBLIC keys of accounts for safety.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = MintingAccountData.class)))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<MintingAccountData> getMintingAccounts() {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<MintingAccountData> mintingAccounts = repository.getAccountRepository().getMintingAccounts();

			// Expand with reward-share data where appropriate
			mintingAccounts = mintingAccounts.stream().map(mintingAccountData -> {
				byte[] publicKey = mintingAccountData.getPublicKey();

				RewardShareData rewardShareData = null;
				try {
					rewardShareData = repository.getAccountRepository().getRewardShare(publicKey);
				} catch (DataException e) {
					// ignore
				}

				return new MintingAccountData(mintingAccountData, rewardShareData);
			}).collect(Collectors.toList());

			return mintingAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/mintingaccounts")
	@Operation(
		summary = "Add private key of account/reward-share for use by BlockMinter to mint blocks",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE, ApiError.CANNOT_MINT})
	@SecurityRequirement(name = "apiKey")
	public String addMintingAccount(String seed58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] seed = Base58.decode(seed58.trim());

			// Check seed is valid
			PrivateKeyAccount mintingAccount = new PrivateKeyAccount(repository, seed);

			// Qortal: account must derive to known reward-share public key
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccount.getPublicKey());
			if (rewardShareData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			// Qortal: check reward-share's minting account is still allowed to mint
			Account rewardShareMintingAccount = new Account(repository, rewardShareData.getMinter());
			if (!rewardShareMintingAccount.canMint())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.CANNOT_MINT);

			MintingAccountData mintingAccountData = new MintingAccountData(mintingAccount.getPrivateKey(), mintingAccount.getPublicKey());

			repository.getAccountRepository().save(mintingAccountData);
			repository.saveChanges();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@DELETE
	@Path("/mintingaccounts")
	@Operation(
		summary = "Remove account/reward-share from use by BlockMinter, using public or private key",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "public or private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String deleteMintingAccount(String key58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] key = Base58.decode(key58.trim());

			if (repository.getAccountRepository().delete(key) == 0)
				return "false";

			repository.saveChanges();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@GET
	@Path("/logs")
	@Operation(
		summary = "Return logs entries",
		description = "Limit pegged to 500 max",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	public String fetchLogs(@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		LoggerContext loggerContext = (LoggerContext) LogManager.getContext();
		RollingFileAppender fileAppender = (RollingFileAppender) loggerContext.getConfiguration().getAppenders().values().stream().filter(appender -> appender instanceof RollingFileAppender).findFirst().get();

		String filename = fileAppender.getManager().getFileName();
		java.nio.file.Path logPath = Paths.get(filename);

		try {
			List<String> logLines = Files.readAllLines(logPath);

			// Slicing
			if (reverse != null && reverse)
				logLines = Lists.reverse(logLines);

			// offset out of bounds?
			if (offset != null && (offset < 0 || offset >= logLines.size()))
				return "";

			if (offset != null) {
				offset = Math.min(offset, logLines.size() - 1);
				logLines.subList(0, offset).clear();
			}

			// invalid limit
			if (limit != null && limit <= 0)
				return "";

			if (limit != null)
				limit = Math.min(limit, MAX_LOG_LINES);
			else
				limit = MAX_LOG_LINES;

			limit = Math.min(limit, logLines.size());

			logLines.subList(limit - 1, logLines.size()).clear();

			return String.join("\n", logLines);
		} catch (IOException e) {
			return "";
		}
	}

	@POST
	@Path("/orphan")
	@Operation(
		summary = "Discard blocks back to given height.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "0"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_HEIGHT, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String orphan(String targetHeightString) {
		Security.checkApiCallAllowed(request);

		try {
			int targetHeight = Integer.parseUnsignedInt(targetHeightString);

			if (targetHeight <= 0 || targetHeight > Controller.getInstance().getChainHeight())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);

			if (BlockChain.orphan(targetHeight))
				return "true";
			else
				return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);
		}
	}

	@POST
	@Path("/forcesync")
	@Operation(
		summary = "Forcibly synchronize to given peer.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "node2.qortal.org"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String forceSync(String targetPeerAddress) {
		Security.checkApiCallAllowed(request);

		try {
			// Try to resolve passed address to make things easier
			PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();

			List<Peer> peers = Network.getInstance().getHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().equals(resolvedAddress)).findFirst().orElse(null);

			if (targetPeer == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			// Try to grab blockchain lock
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
			if (!blockchainLock.tryLock(30000, TimeUnit.MILLISECONDS))
				return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();

			SynchronizationResult syncResult;
			try {
				do {
					syncResult = Controller.getInstance().actuallySynchronize(targetPeer, true);
				} while (syncResult == SynchronizationResult.OK);
			} finally {
				blockchainLock.unlock();
			}

			return syncResult.name();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (InterruptedException e) {
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();
		}
	}

	@GET
	@Path("/repository")
	@Operation(
		summary = "Export sensitive/node-local data from repository.",
		description = "Exports data to .script files on local machine"
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String exportRepository() {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				repository.exportNodeLocalData();
				return "true";
			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform export
			return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository")
	@Operation(
		summary = "Import data into repository.",
		description = "Imports data from file on local machine. Filename is forced to 'import.script' if apiKey is not set.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "MintingAccounts.script"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String importRepository(String filename) {
		Security.checkApiCallAllowed(request);

		// Hard-coded because it's too dangerous to allow user-supplied filenames in weaker security contexts
		if (Settings.getInstance().getApiKey() == null)
			filename = "import.script";

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				repository.importDataFromFile(filename);
				repository.saveChanges();

				return "true";
			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform import
			return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/repository")
	@Operation(
		summary = "Perform maintenance on repository.",
		description = "Requires enough free space to rebuild repository. This will pause your node for a while."
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public void performRepositoryMaintenance() {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				repository.performPeriodicMaintenance();
			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// No big deal
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
