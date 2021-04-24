package org.forgerock.openig.ob.filter;

import java.security.cert.X509Certificate;

import org.bouncycastle.asn1.x500.style.BCStyle;
import org.forgerock.http.Filter;
import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.ob.utils.CertificateUtils;
import org.forgerock.services.context.AttributesContext;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.Promises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CertificateExtensionValidatorFilter implements Filter {

	private Logger logger = LoggerFactory.getLogger(CertificateExtensionValidatorFilter.class);

	private static final String TPP_ID = "tppId";

	private String routeRole;
	private String clientCertificateHeaderName;
	private Boolean checkCertificateExtensions;

	@Override
	public Promise<Response, NeverThrowsException> filter(Context context, Request request, Handler next) {
		logger.info("Route roles: " + routeRole);
		String[] arrayRoles = routeRole.split(",");
		String transportCertificate = request.getHeaders().getFirst(clientCertificateHeaderName);
		if (transportCertificate != null && !transportCertificate.isEmpty()) {
			transportCertificate = CertificateUtils.formatTransportCertificate(transportCertificate);
			X509Certificate certificate = CertificateUtils.initializeCertificate(transportCertificate);
			if (certificate != null) {
				if (checkCertificateExtensions != null && checkCertificateExtensions) {
					String certificateExtensionsAsString = CertificateUtils.getCertificateExtensions(certificate);
					logger.info("Certificate extensions: " + certificateExtensionsAsString);
					boolean roleAccepted = false;
					if (certificateExtensionsAsString != null) {
						certificateExtensionsAsString = certificateExtensionsAsString.toLowerCase();
						for (String role : arrayRoles) {
							if (role != null && certificateExtensionsAsString.contains(role.toLowerCase())) {
								roleAccepted = true;
							}
						}
					}

					if (!roleAccepted) {
						logger.warn("The roles configured in the route were not found in the {} certificate.",
								clientCertificateHeaderName);
						Response response = new Response(Status.UNAUTHORIZED);
						return Promises.newResultPromise(response);
					}
				}

				String tppId = CertificateUtils.getCertificateSubjectDnProperty(certificate, BCStyle.OU);
				context.asContext(AttributesContext.class).getAttributes().put(TPP_ID, tppId);

			} else {
				Response response = new Response(Status.UNAUTHORIZED);
				return Promises.newResultPromise(response);
			}
		} else {
			Response response = new Response(Status.UNAUTHORIZED);
			return Promises.newResultPromise(response);
		}
		return next.handle(context, request);
	}

	/**
	 * Create and initialize the filter, based on the configuration. The filter
	 * object is stored in the heap.
	 */
	public static class Heaplet extends GenericHeaplet {

		/**
		 * Create the filter object in the heap, setting the header name and value for
		 * the filter, based on the configuration.
		 *
		 * @return The filter object.
		 * @throws HeapException Failed to create the object.
		 */
		@Override
		public Object create() throws HeapException {
			CertificateExtensionValidatorFilter filter = new CertificateExtensionValidatorFilter();
			filter.routeRole = config.get("routeRole").as(evaluatedWithHeapProperties()).required().asString();
			filter.clientCertificateHeaderName = config.get("clientCertificateHeaderName")
					.as(evaluatedWithHeapProperties()).required().asString();
			filter.checkCertificateExtensions = config.get("checkCertificateExtensions")
					.as(evaluatedWithHeapProperties()).asBoolean();
			return filter;
		}
	}

}