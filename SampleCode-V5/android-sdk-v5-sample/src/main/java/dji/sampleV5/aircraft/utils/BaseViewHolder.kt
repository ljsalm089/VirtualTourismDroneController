package dji.sampleV5.aircraft.utils

import android.util.SparseArray
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {

    private val cacheView = SparseArray<View>()

    fun <T :View> getView(id: Int): T? {
        var view = cacheView.get(id)

        if (null == view) {
            view = itemView.findViewById(id)
            view?.let{
                cacheView.put(id, it)
            }
        }
        return view as? T
    }

}