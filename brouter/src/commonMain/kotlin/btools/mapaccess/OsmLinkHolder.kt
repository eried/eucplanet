/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.mapaccess

interface OsmLinkHolder {
  fun setNextForLink(holder: OsmLinkHolder?)

  fun getNextForLink(): OsmLinkHolder?
}
